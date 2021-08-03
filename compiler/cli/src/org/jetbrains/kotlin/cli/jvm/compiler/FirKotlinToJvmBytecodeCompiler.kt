/*
 * Copyright 2010-2021 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.cli.jvm.compiler

import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.StandardFileSystems
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.VirtualFileSystem
import org.jetbrains.kotlin.analyzer.common.CommonPlatformAnalyzerServices
import org.jetbrains.kotlin.asJava.FilteredJvmDiagnostics
import org.jetbrains.kotlin.backend.common.phaser.PhaseConfig
import org.jetbrains.kotlin.backend.jvm.JvmGeneratorExtensionsImpl
import org.jetbrains.kotlin.backend.jvm.JvmIrCodegenFactory
import org.jetbrains.kotlin.backend.jvm.jvmPhases
import org.jetbrains.kotlin.cli.common.CLIConfigurationKeys
import org.jetbrains.kotlin.cli.common.CommonCompilerPerformanceManager
import org.jetbrains.kotlin.cli.common.checkKotlinPackageUsage
import org.jetbrains.kotlin.cli.common.fir.FirDiagnosticsCompilerResultsReporter
import org.jetbrains.kotlin.cli.common.messages.AnalyzerWithCompilerReport
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSeverity.STRONG_WARNING
import org.jetbrains.kotlin.cli.common.messages.MessageCollector
import org.jetbrains.kotlin.cli.jvm.config.jvmClasspathRoots
import org.jetbrains.kotlin.cli.jvm.config.jvmModularRoots
import org.jetbrains.kotlin.codegen.ClassBuilderFactories
import org.jetbrains.kotlin.codegen.CodegenFactory
import org.jetbrains.kotlin.codegen.state.GenerationState
import org.jetbrains.kotlin.config.*
import org.jetbrains.kotlin.fir.DependencyListForCliModule
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.analysis.diagnostics.FirDiagnostic
import org.jetbrains.kotlin.fir.backend.Fir2IrResult
import org.jetbrains.kotlin.fir.backend.jvm.FirJvmBackendClassResolver
import org.jetbrains.kotlin.fir.backend.jvm.FirJvmBackendExtension
import org.jetbrains.kotlin.fir.checkers.registerExtendedCommonCheckers
import org.jetbrains.kotlin.fir.declarations.FirFile
import org.jetbrains.kotlin.fir.java.FirProjectSessionProvider
import org.jetbrains.kotlin.fir.moduleData
import org.jetbrains.kotlin.fir.pipeline.buildFirFromKtFiles
import org.jetbrains.kotlin.fir.pipeline.convertToIr
import org.jetbrains.kotlin.fir.pipeline.runCheckers
import org.jetbrains.kotlin.fir.pipeline.runResolution
import org.jetbrains.kotlin.fir.resolve.ScopeSession
import org.jetbrains.kotlin.fir.session.FirSessionFactory
import org.jetbrains.kotlin.fir.session.FirSessionFactory.createSessionWithDependencies
import org.jetbrains.kotlin.fir.session.environment.AbstractProjectEnvironment
import org.jetbrains.kotlin.fir.session.environment.AbstractProjectFileSearchScope
import org.jetbrains.kotlin.load.kotlin.incremental.IncrementalPackagePartProvider
import org.jetbrains.kotlin.load.kotlin.incremental.components.IncrementalCompilationComponents
import org.jetbrains.kotlin.modules.Module
import org.jetbrains.kotlin.modules.TargetId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.platform.CommonPlatforms
import org.jetbrains.kotlin.platform.TargetPlatform
import org.jetbrains.kotlin.platform.jvm.JvmPlatforms
import org.jetbrains.kotlin.progress.ProgressIndicatorAndCompilationCanceledStatus
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.resolve.PlatformDependentAnalyzerServices
import org.jetbrains.kotlin.resolve.jvm.platform.JvmPlatformAnalyzerServices
import org.jetbrains.kotlin.resolve.multiplatform.isCommonSource
import org.jetbrains.kotlin.utils.addToStdlib.flattenTo
import org.jetbrains.kotlin.utils.addToStdlib.runIf
import org.jetbrains.kotlin.utils.newLinkedHashMapWithExpectedSize
import java.io.File
import kotlin.collections.set

object FirKotlinToJvmBytecodeCompiler {
    fun compileModulesUsingFrontendIR(
        environment: KotlinCoreEnvironment,
        buildFile: File?,
        chunk: List<Module>,
        extendedAnalysisMode: Boolean
    ): Boolean {
        val project = environment.project
        val performanceManager = environment.configuration.get(CLIConfigurationKeys.PERF_MANAGER)

        environment.messageCollector.report(
            STRONG_WARNING,
            "ATTENTION!\n This build uses in-dev FIR: \n  -Xuse-fir"
        )

        val projectConfiguration = environment.configuration
        val localFileSystem = VirtualFileManager.getInstance().getFileSystem(StandardFileSystems.FILE_PROTOCOL)
        val outputs = newLinkedHashMapWithExpectedSize<Module, GenerationState>(chunk.size)
        val targetIds = environment.configuration.get(JVMConfigurationKeys.MODULES)?.map(::TargetId)
        val incrementalComponents = environment.configuration.get(JVMConfigurationKeys.INCREMENTAL_COMPILATION_COMPONENTS)
        val isMultiModuleChunk = chunk.size > 1

        val projectEnvironment = PsiBasedProjectEnvironment(project, localFileSystem, { environment.createPackagePartProvider(it) })

        for (module in chunk) {
            val moduleConfiguration = projectConfiguration.applyModuleProperties(module, buildFile)
            val context = CompilationContext(
                module,
                environment,
                projectEnvironment,
                environment.messageCollector,
                moduleConfiguration,
                localFileSystem,
                isMultiModuleChunk,
                buildFile,
                performanceManager,
                targetIds,
                incrementalComponents,
                extendedAnalysisMode
            )
            val generationState = context.compileModule() ?: return false
            outputs[module] = generationState

            Disposer.dispose(environment.project)
        }

        val mainClassFqName: FqName? =
            if (chunk.size == 1 && projectConfiguration.get(JVMConfigurationKeys.OUTPUT_JAR) != null)
                TODO(".jar output is not yet supported for -Xuse-fir: KT-42868")
            else null

        return writeOutputs(environment, projectConfiguration, chunk, outputs, mainClassFqName)
    }

    private fun CompilationContext.compileModule(): GenerationState? {
        performanceManager?.notifyAnalysisStarted()
        ProgressIndicatorAndCompilationCanceledStatus.checkCanceled()

        val ktFiles = module.getSourceFiles(environment.getSourceFiles(), localFileSystem, isMultiModuleChunk, buildFile)
        if (!checkKotlinPackageUsage(environment, ktFiles)) return null

        val firResult = runFrontend(ktFiles).also {
            performanceManager?.notifyAnalysisFinished()
        } ?: return null

        performanceManager?.notifyGenerationStarted()
        performanceManager?.notifyIRTranslationStarted()

        val extensions = JvmGeneratorExtensionsImpl(moduleConfiguration)
        val fir2IrResult = firResult.session.convertToIr(firResult.scopeSession, firResult.fir, extensions)

        performanceManager?.notifyIRTranslationFinished()

        val generationState = runBackend(
            ktFiles,
            fir2IrResult,
            extensions,
            firResult.session
        )

        performanceManager?.notifyIRGenerationFinished()
        performanceManager?.notifyGenerationFinished()
        ProgressIndicatorAndCompilationCanceledStatus.checkCanceled()

        return generationState
    }

    private class FirResult(
        val session: FirSession,
        val scopeSession: ScopeSession,
        val fir: List<FirFile>
    )

    private fun CompilationContext.runFrontend(ktFiles: List<KtFile>): FirResult? {
        @Suppress("NAME_SHADOWING")
        var ktFiles = ktFiles
        val syntaxErrors = ktFiles.fold(false) { errorsFound, ktFile ->
            AnalyzerWithCompilerReport.reportSyntaxErrors(ktFile, messageCollector).isHasErrors or errorsFound
        }

        var sourceScope = (projectEnvironment as PsiBasedProjectEnvironment).getSearchScopeByPsiFiles(ktFiles) +
                projectEnvironment.getSearchScopeForProjectJavaSources()

        var librariesScope = projectEnvironment.getSearchScopeForProjectLibraries()

        val providerAndScopeForIncrementalCompilation = createComponentsForIncrementalCompilation(sourceScope)

        providerAndScopeForIncrementalCompilation?.scope?.let {
            librariesScope -= it
        }

        val languageVersionSettings = moduleConfiguration.languageVersionSettings

        val commonKtFiles = ktFiles.filter { it.isCommonSource == true }

        val sessionProvider = FirProjectSessionProvider()

        fun createSession(
            name: String,
            platform: TargetPlatform,
            analyzerServices: PlatformDependentAnalyzerServices,
            sourceScope: AbstractProjectFileSearchScope,
            dependenciesConfigurator: DependencyListForCliModule.Builder.() -> Unit = {}
        ): FirSession {
            return createSessionWithDependencies(
                Name.identifier(name),
                platform,
                analyzerServices,
                externalSessionProvider = sessionProvider,
                projectEnvironment,
                languageVersionSettings,
                sourceScope,
                librariesScope,
                lookupTracker = environment.configuration.get(CommonConfigurationKeys.LOOKUP_TRACKER),
                providerAndScopeForIncrementalCompilation,
                dependenciesConfigurator = {
                    dependencies(moduleConfiguration.jvmClasspathRoots.map { it.toPath() })
                    dependencies(moduleConfiguration.jvmModularRoots.map { it.toPath() })
                    friendDependencies(moduleConfiguration[JVMConfigurationKeys.FRIEND_PATHS] ?: emptyList())
                    dependenciesConfigurator()
                }
            ) {
                if (extendedAnalysisMode) {
                    registerExtendedCommonCheckers()
                }
            }
        }

        val commonSession = runIf(
            languageVersionSettings.supportsFeature(LanguageFeature.MultiPlatformProjects) && commonKtFiles.isNotEmpty()
        ) {
            val commonSourcesScope = projectEnvironment.getSearchScopeByPsiFiles(commonKtFiles)
            sourceScope -= commonSourcesScope
            ktFiles = ktFiles.filterNot { it.isCommonSource == true }
            createSession(
                "${module.getModuleName()}-common",
                CommonPlatforms.defaultCommonPlatform,
                CommonPlatformAnalyzerServices,
                commonSourcesScope
            )
        }

        val session = createSession(
            module.getModuleName(),
            JvmPlatforms.unspecifiedJvmPlatform,
            JvmPlatformAnalyzerServices,
            sourceScope
        ) {
            if (commonSession != null) {
                sourceDependsOnDependencies(listOf(commonSession.moduleData))
            }
            friendDependencies(module.getFriendPaths())
        }

        val commonRawFir = commonSession?.buildFirFromKtFiles(commonKtFiles)
        val rawFir = session.buildFirFromKtFiles(ktFiles)

        val allFirDiagnostics = mutableListOf<FirDiagnostic>()
        commonSession?.apply {
            val (commonScopeSession, commonFir) = runResolution(commonRawFir!!)
            runCheckers(commonScopeSession, commonFir).values.flattenTo(allFirDiagnostics)
        }

        val (scopeSession, fir) = session.runResolution(rawFir)
        session.runCheckers(scopeSession, fir).values.flattenTo(allFirDiagnostics)

        val hasErrors = FirDiagnosticsCompilerResultsReporter.reportDiagnostics(allFirDiagnostics, messageCollector)

        return if (syntaxErrors || hasErrors) null else FirResult(session, scopeSession, fir)
    }

    private fun CompilationContext.createComponentsForIncrementalCompilation(
        sourceScope: AbstractProjectFileSearchScope
    ): FirSessionFactory.ProviderAndScopeForIncrementalCompilation? {
        if (targetIds == null || incrementalComponents == null) return null
        val directoryWithIncrementalPartsFromPreviousCompilation =
            moduleConfiguration[JVMConfigurationKeys.OUTPUT_DIRECTORY]
                ?: return null
        val incrementalCompilationScope = directoryWithIncrementalPartsFromPreviousCompilation.walk()
            .filter { it.extension == "class" }
            .let { projectEnvironment.getSearchScopeByIoFiles(it.asIterable()) }
            .takeIf { !it.isEmpty }
            ?: return null
        val packagePartProvider = IncrementalPackagePartProvider(
            projectEnvironment.getPackagePartProvider(sourceScope),
            targetIds.map(incrementalComponents::getIncrementalCache)
        )
        return FirSessionFactory.ProviderAndScopeForIncrementalCompilation(packagePartProvider, incrementalCompilationScope)
    }

    private fun CompilationContext.runBackend(
        ktFiles: List<KtFile>,
        fir2IrResult: Fir2IrResult,
        extensions: JvmGeneratorExtensionsImpl,
        session: FirSession
    ): GenerationState {
        val (moduleFragment, symbolTable, components) = fir2IrResult
        val dummyBindingContext = NoScopeRecordCliBindingTrace().bindingContext
        val codegenFactory = JvmIrCodegenFactory(
            moduleConfiguration,
            moduleConfiguration.get(CLIConfigurationKeys.PHASE_CONFIG) ?: PhaseConfig(jvmPhases),
            jvmGeneratorExtensions = extensions
        )

        val generationState = GenerationState.Builder(
            environment.project, ClassBuilderFactories.BINARIES,
            moduleFragment.descriptor, dummyBindingContext, ktFiles,
            moduleConfiguration
        ).codegenFactory(
            codegenFactory
        ).withModule(
            module
        ).onIndependentPartCompilationEnd(
            createOutputFilesFlushingCallbackIfPossible(moduleConfiguration)
        ).isIrBackend(
            true
        ).jvmBackendClassResolver(
            FirJvmBackendClassResolver(components)
        ).build()

        ProgressIndicatorAndCompilationCanceledStatus.checkCanceled()

        performanceManager?.notifyIRLoweringStarted()
        generationState.beforeCompile()
        codegenFactory.generateModuleInFrontendIRMode(
            generationState, moduleFragment, symbolTable, extensions, FirJvmBackendExtension(session, components)
        ) {
            performanceManager?.notifyIRLoweringFinished()
            performanceManager?.notifyIRGenerationStarted()
        }
        CodegenFactory.doCheckCancelled(generationState)
        generationState.factory.done()

        ProgressIndicatorAndCompilationCanceledStatus.checkCanceled()

        AnalyzerWithCompilerReport.reportDiagnostics(
            FilteredJvmDiagnostics(
                generationState.collectedExtraJvmDiagnostics,
                dummyBindingContext.diagnostics
            ),
            messageCollector
        )
        ProgressIndicatorAndCompilationCanceledStatus.checkCanceled()

        return generationState
    }

    private class CompilationContext(
        val module: Module,
        val environment: KotlinCoreEnvironment,
        val projectEnvironment: AbstractProjectEnvironment,
        val messageCollector: MessageCollector,
        val moduleConfiguration: CompilerConfiguration,
        val localFileSystem: VirtualFileSystem,
        val isMultiModuleChunk: Boolean,
        val buildFile: File?,
        val performanceManager: CommonCompilerPerformanceManager?,
        val targetIds: List<TargetId>?,
        val incrementalComponents: IncrementalCompilationComponents?,
        val extendedAnalysisMode: Boolean
    )
}
