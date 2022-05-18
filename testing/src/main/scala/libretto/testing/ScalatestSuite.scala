package libretto.testing

import org.scalatest.funsuite.AnyFunSuite

abstract class ScalatestSuite extends AnyFunSuite {
  def tests: Tests

  private def registerTests(): Unit = {
    val tests = this.tests
    for {
      testExecutor <- tests.testExecutors
    } {
      registerTests[tests.TDSL](testExecutor, prefix = "", tests.testCases(using testExecutor.testDsl))
    }
  }

  private def registerTests[TDSL <: TestDsl](
    testExecutor: TestExecutor[TDSL],
    prefix: String,
    cases: List[(String, Tests.Case[testExecutor.testDsl.type])],
  ): Unit = {
    for {
      (testName, testCase) <- cases
    } {
      testCase match {
        case c: Tests.Case.Single[testExecutor.testDsl.type] =>
          test(s"$prefix$testName (executed by ${testExecutor.name})") {
            testExecutor.runTestCase(c.body, c.conductor, c.postStop) match {
              case TestResult.Success(_) =>
                // do nothing
              case TestResult.Failure(msg) =>
                fail(msg)
              case TestResult.Crash(e) =>
                fail(s"Crashed with ${e.getClass.getCanonicalName}: ${e.getMessage}", e)
            }
          }
        case Tests.Case.Multiple(cases) =>
          registerTests(testExecutor, s"$prefix$testName.", cases)
      }
    }
  }

  registerTests()
}
