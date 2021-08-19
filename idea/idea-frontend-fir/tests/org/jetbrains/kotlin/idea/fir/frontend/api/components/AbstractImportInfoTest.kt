/*
 * Copyright 2010-2021 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.idea.fir.frontend.api.components

import org.jetbrains.kotlin.idea.fir.executeOnPooledThreadInReadAction
import org.jetbrains.kotlin.idea.fir.frontend.api.test.framework.AbstractHLApiSingleFileTest
import org.jetbrains.kotlin.idea.frontend.api.analyse
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.test.model.TestModule
import org.jetbrains.kotlin.test.services.TestServices
import org.jetbrains.kotlin.test.services.assertions

abstract class AbstractImportInfoTest : AbstractHLApiSingleFileTest() {
    override fun doTestByFileStructure(ktFile: KtFile, module: TestModule, testServices: TestServices) {
        val actualText = buildString {
            executeOnPooledThreadInReadAction {
                analyse(ktFile) {
                    appendLine("[defaultImportPaths]")
                    for (path in ktFile.defaultImportPaths) {
                        appendLine(path.toString())
                    }
                }
            }
        }

        testServices.assertions.assertEqualsToFile(testDataFileSibling(".txt"), actualText)
    }
}