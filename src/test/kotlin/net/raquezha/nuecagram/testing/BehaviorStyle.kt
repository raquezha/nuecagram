/*
 * Adapted from infix-de/testBalloon's BehaviorStyle example (Apache-2.0).
 */
package net.raquezha.nuecagram.testing

import de.infix.testBalloon.framework.core.TestConfig
import de.infix.testBalloon.framework.core.TestElement
import de.infix.testBalloon.framework.core.TestSuite
import de.infix.testBalloon.framework.core.TestSuiteScope
import de.infix.testBalloon.framework.core.parameter
import de.infix.testBalloon.framework.shared.TestElementName
import de.infix.testBalloon.framework.shared.TestRegistering

@Suppress("TestFunctionName")
@TestRegistering
fun <Context : Any> TestSuiteScope.Scenario(
    @TestElementName(prefix = "Scenario: ") description: String,
    context: suspend () -> Context,
    testConfig: TestConfig = TestConfig,
    content: StepDefinition<Context>.() -> Unit,
) {
    testSuite("Scenario: $description", testConfig = testConfig) {
        StepDefinition(testSuiteInScope, context).apply {
            content()
            register()
        }
    }
}

@Suppress("TestFunctionName")
class StepDefinition<Context : Any>(
    override val testSuiteInScope: TestSuite,
    private val context: suspend () -> Context,
) : TestSuiteScope {
    private class Step<Value : Any>(val description: String, val action: suspend Value.() -> Unit)

    private val steps = mutableListOf<Step<Context>>()

    fun Given(description: String, action: suspend Context.() -> Unit) {
        steps.add(Step("Given $description", action))
    }

    fun When(description: String, action: suspend Context.() -> Unit) {
        steps.add(Step("When $description", action))
    }

    fun And(description: String, action: suspend Context.() -> Unit) {
        steps.add(Step("And $description", action))
    }

    fun Then(description: String, action: suspend Context.() -> Unit) {
        steps.add(Step("Then $description", action))
    }

    internal fun register() {
        when (testSuiteInScope.behaviorStyle()) {
            BehaviorStyle.Linear -> registerLinearSteps()
            BehaviorStyle.Hierarchical -> registerHierarchicalSteps()
        }
    }

    private fun registerLinearSteps() {
        testFixture { context() }.asParameterForAll {
            steps.forEach { step ->
                test(step.description) { scenario -> step.action(scenario) }
            }
        }
    }

    private fun registerHierarchicalSteps() {
        if (steps.isEmpty()) return
        val actions = steps.map { it.action }
        val registerSteps =
            steps.dropLast(1).foldRight(
                fun TestSuiteScope.() {
                    test(steps.last().description) {
                        with(context()) {
                            actions.forEach { action -> action() }
                        }
                    }
                },
            ) { step, nested ->
                fun TestSuiteScope.() {
                    testSuite(step.description, content = nested)
                }
            }
        registerSteps()
    }
}

fun TestConfig.behaviorStyle(value: BehaviorStyle) = parameter(BehaviorStyleParameter.Key) {
    BehaviorStyleParameter(value)
}

enum class BehaviorStyle {
    Linear,
    Hierarchical,
}

private class BehaviorStyleParameter(val value: BehaviorStyle) : TestElement.KeyedParameter(Key) {
    companion object Key : TestElement.KeyedParameter.Key<BehaviorStyleParameter>
}

private fun TestElement.behaviorStyle() =
    testElementParameter(BehaviorStyleParameter.Key)?.value ?: BehaviorStyle.Linear
