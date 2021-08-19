/*
 * Copyright 2010-2021 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.idea.frontend.api.components

import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.resolve.ImportPath

public abstract class KtImportInfoProvider : KtAnalysisSessionComponent() {
    public abstract fun getDefaultImports(file: KtFile): Set<ImportPath>
}

public interface KtImportInfoProviderMixIn : KtAnalysisSessionMixIn {

    /**
     * All the default import paths for a given file.
     */
    public val KtFile.defaultImportPaths: Set<ImportPath> get() = analysisSession.importInfoProvider.getDefaultImports(this)
}