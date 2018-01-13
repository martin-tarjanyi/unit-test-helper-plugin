package com.martin.intellij.plugin.unittest.component.impl

import com.intellij.lang.java.JavaImportOptimizer
import com.intellij.openapi.project.Project
import com.intellij.psi.*
import com.intellij.psi.codeStyle.CodeStyleManager
import com.intellij.psi.util.PsiTypesUtil
import com.martin.intellij.plugin.common.util.createStatementFromText
import com.martin.intellij.plugin.common.util.findIndefiniteArticle
import com.martin.intellij.plugin.common.util.generateName
import com.martin.intellij.plugin.common.util.isPublic
import com.martin.intellij.plugin.mockbuilder.component.MockBuilderGeneratorProjectComponent
import com.martin.intellij.plugin.unittest.component.UnitTestGeneratorProjectComponent

class UnitTestGeneratorProjectComponentImpl(project: Project,
                                            private val mockBuilderGeneratorProjectComponent: MockBuilderGeneratorProjectComponent)
    : UnitTestGeneratorProjectComponent
{
    private val javaDirectoryService = JavaDirectoryService.getInstance()
    private val elementFactory = JavaPsiFacade.getElementFactory(project)
    private val codeStyleManager = CodeStyleManager.getInstance(project)
    private val javaImportOptimizer = JavaImportOptimizer()

    override fun execute(subjectClass: PsiClass, psiDirectory: PsiDirectory): PsiClass
    {
        val unitTestClass = javaDirectoryService.createClass(psiDirectory, "${subjectClass.name}Test")

        val primaryConstructor = subjectClass.allMethods.filter { it.isConstructor }.maxBy { it.parameters.size } ?: throw IllegalStateException(
                "Subject class does not have any constructor.")

        val constructorParameters = primaryConstructor.parameterList.parameters

        val methodsToTest = subjectClass.allMethods
                .filter { it.isPublic() and !it.isConstructor and (it.containingClass?.name == subjectClass.name) }

        val parametersForMethods = methodsToTest.map { it.parameterList.parameters }
                .flatMap { it.toList() }.distinctBy { it.type.toString() + it.name }

        val mocks = constructorParameters.associateBy({ param: PsiParameter -> param.name!! }, { param: PsiParameter ->
            val paramClass = PsiTypesUtil.getPsiClass(param.type)!!
            mockBuilderGeneratorProjectComponent.execute(paramClass, psiDirectory)
        })

        unitTestClass.apply {
            addFieldsForMethodParameters(parametersForMethods)
            addFieldsForMockedDependencies(constructorParameters)
            addFieldsForReturnValues(methodsToTest)
            addGivenStepsForMethodParameters(parametersForMethods)
            addGivenStepForMockedDependencies(mocks)
            addWhenMethods(methodsToTest, primaryConstructor, subjectClass)
        }

        javaImportOptimizer.processFile(unitTestClass.containingFile)
        codeStyleManager.reformat(unitTestClass)

        return unitTestClass
    }

    private fun PsiClass.addFieldsForReturnValues(methodsToTest: List<PsiMethod>)
    {
        methodsToTest.filter { it.returnType != PsiType.VOID }.forEach {
            add(elementFactory.createField("actual${it.returnType!!.generateName().capitalize()}", it.returnType!!))
        }
    }

    private fun PsiClass.addFieldsForMockedDependencies(constructorParameters: Array<out PsiParameter>)
    {
        constructorParameters.forEach { add(elementFactory.createField(it.name!!, it.type)) }
    }

    private fun PsiClass.addWhenMethods(methodsToTest: List<PsiMethod>, primaryConstructor: PsiMethod, subjectClass: PsiClass)
    {
        methodsToTest.forEach { methodToTest ->
            add(elementFactory.createMethod("when${methodToTest.name.capitalize()}IsCalled", PsiType.VOID).apply {
                body?.apply {
                    val parametersForConstructorInvocation = primaryConstructor.parameterList.parameters.map { it.name }.joinToString()
                    val methodParameters = methodToTest.parameterList.parameters.map { it.name }.joinToString()
                    val returnType = methodToTest.returnType
                    if (returnType == null || returnType == PsiType.VOID)
                    {
                        add(elementFactory.createStatementFromText("new ${subjectClass.name}($parametersForConstructorInvocation).${methodToTest.name}($methodParameters);"))
                    } else
                    {
                        val generatedNameForReturnValue = methodToTest.returnType!!.generateName().capitalize()
                        add(elementFactory.createStatementFromText(
                                "this.actual$generatedNameForReturnValue = new ${subjectClass.name}($parametersForConstructorInvocation)" +
                                        ".${methodToTest.name}($methodParameters);"))

                    }
                }
            })
        }
    }

    private fun PsiClass.addGivenStepForMockedDependencies(mocks: Map<String, PsiClass>)
    {
        mocks.forEach { parameterName, mockClass ->
            val indefiniteArticle = parameterName.findIndefiniteArticle().capitalize()
            add(elementFactory.createMethod("given$indefiniteArticle${parameterName.capitalize()}", PsiType.VOID).apply {
                body?.apply {
                    val factoryMethod = mockClass.allMethods.find {
                        it.modifierList.hasModifierProperty(PsiModifier.STATIC)
                    }
                    add(elementFactory.createStatementFromText("this.$parameterName = ${mockClass.name}.${factoryMethod!!.name}().build();"))
                }
            })
        }
    }

    private fun PsiClass.addFieldsForMethodParameters(parametersForMethods: List<PsiParameter>)
    {
        parametersForMethods.forEach {
            add(elementFactory.createField(it.name!!, it.type))
        }
    }

    private fun PsiClass.addGivenStepsForMethodParameters(parametersForMethods: List<PsiParameter>)
    {
        parametersForMethods.forEach { parameter ->
            val parameterName = parameter.name ?: throw IllegalStateException("Constructor parameter should have a name.")
            val indefiniteArticle = parameterName.findIndefiniteArticle().capitalize()
            add(elementFactory.createMethod("given$indefiniteArticle${parameterName.capitalize()}", PsiType.VOID).apply {
                parameterList.apply {
                    add(elementFactory.createParameter(parameterName, parameter.type))
                }
                body?.apply {
                    add(elementFactory.createStatementFromText("this.$parameterName = $parameterName;"))
                }
            })
        }
    }
}