/*
 * Copyright 2010-2021 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.analysis.api.descriptors.components

import org.jetbrains.kotlin.analysis.api.components.KtTypeInfoProvider
import org.jetbrains.kotlin.analysis.api.descriptors.KtFe10AnalysisSession
import org.jetbrains.kotlin.analysis.api.descriptors.types.base.KtFe10Type
import org.jetbrains.kotlin.analysis.api.tokens.ValidityToken
import org.jetbrains.kotlin.analysis.api.types.KtType
import org.jetbrains.kotlin.analysis.api.withValidityAssertion
import org.jetbrains.kotlin.builtins.functions.FunctionClassKind
import org.jetbrains.kotlin.builtins.getFunctionalClassKind
import org.jetbrains.kotlin.load.java.sam.JavaSingleAbstractMethodUtils
import org.jetbrains.kotlin.types.TypeUtils

internal class KtFe10TypeInfoProvider(override val analysisSession: KtFe10AnalysisSession) : KtTypeInfoProvider() {
    override val token: ValidityToken
        get() = analysisSession.token

    override fun isFunctionalInterfaceType(type: KtType): Boolean = withValidityAssertion {
        require(type is KtFe10Type)
        return JavaSingleAbstractMethodUtils.isSamType(type.type)
    }

    override fun getFunctionClassKind(type: KtType): FunctionClassKind? {
        require(type is KtFe10Type)
        return type.type.constructor.declarationDescriptor?.getFunctionalClassKind()
    }

    override fun canBeNull(type: KtType): Boolean = withValidityAssertion {
        require(type is KtFe10Type)
        return TypeUtils.isNullableType(type.type)
    }
}
