/*
 * Copyright 2010-2021 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.idea.frontend.api.components

import org.jetbrains.kotlin.idea.frontend.api.symbols.KtClassLikeSymbol
import org.jetbrains.kotlin.idea.frontend.api.symbols.KtSamConstructorSymbol
import org.jetbrains.kotlin.name.ClassId

public abstract class KtSamResolver : KtAnalysisSessionComponent() {
    public abstract fun getSamConstructor(ktClassLikeSymbol: KtClassLikeSymbol): KtSamConstructorSymbol?
}

public interface KtSamResolverMixIn : KtAnalysisSessionMixIn {
    public fun KtClassLikeSymbol.getSamConstructor(): KtSamConstructorSymbol? =
        analysisSession.samResolver.getSamConstructor(this)
}
