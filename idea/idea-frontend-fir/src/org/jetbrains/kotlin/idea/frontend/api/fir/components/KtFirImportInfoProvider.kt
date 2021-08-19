/*
 * Copyright 2010-2021 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.idea.frontend.api.fir.components

import org.jetbrains.kotlin.fir.scopes.impl.FirDefaultSimpleImportingScope
import org.jetbrains.kotlin.fir.scopes.impl.FirDefaultStarImportingScope
import org.jetbrains.kotlin.idea.fir.low.level.api.api.LowLevelFirApiFacadeForResolveOnAir.getTowerContextProvider
import org.jetbrains.kotlin.idea.frontend.api.components.KtImportInfoProvider
import org.jetbrains.kotlin.idea.frontend.api.fir.KtFirAnalysisSession
import org.jetbrains.kotlin.idea.frontend.api.tokens.ValidityToken
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.resolve.ImportPath

internal class KtFirImportInfoProvider(
    override val analysisSession: KtFirAnalysisSession,
    override val token: ValidityToken,
) : KtImportInfoProvider(), KtFirAnalysisSessionComponent {
    override fun getDefaultImports(file: KtFile): Set<ImportPath> {
        val context = analysisSession.firResolveState.getTowerContextProvider().getClosestAvailableParentContext(file) ?: return emptySet()
        return context.nonLocalTowerDataElements.flatMapTo(mutableSetOf()) {
            when (val scope = it.scope) {
                is FirDefaultStarImportingScope -> scope.starImports.mapNotNull {
                    ImportPath(it.importedFqName ?: return@mapNotNull null, true)
                }
                is FirDefaultSimpleImportingScope -> scope.simpleImports.values.flatten().mapNotNull {
                    ImportPath(it.importedFqName ?: return@mapNotNull null, false)
                }
                else -> emptySet()
            }
        }
    }
}