# FinBridge Quality Gates

## Blocking Command

Every Java change must run from the repository root using JDK 21:

```bash
./mvnw clean verify
```

The same command is run for pushes and pull requests by `.github/workflows/quality.yml`. Quality failures block integration. Do not bypass gates with skip properties or command-line flags. If an exceptional finding cannot reasonably be fixed, propose a narrow suppression with the specific rule/finding, technical rationale, scope, owner, and review/removal condition; get it approved and documented before applying it.

## Configured Checks

| Gate | Configuration | Blocking policy |
|---|---|---|
| Java version | Maven Enforcer, `[21,22)` | Any other runtime JDK fails the build. Compiler release is 21. |
| SpotBugs | SpotBugs Maven Plugin 4.10.5.0 | Maximum effort, Low threshold, zero allowed findings, fail on analyzer error. |
| PMD | Maven PMD Plugin 3.28.0, PMD target JDK 21, `config/pmd-ruleset.xml` | Error-prone, best-practice, code-style, and selected design rules; all priorities fail. Tests are also analyzed. |
| Size / complexity | PMD NCSS, cyclomatic/cognitive complexity, God Class, nesting and parameter-list rules | Reject class NCSS above 500, method/constructor NCSS above 50, cyclomatic complexity above 10, cognitive complexity above 15; reject selected structural smells. NCSS counts statements rather than physical/comment lines. |
| Tests / missing coverage data | Maven Surefire | `failIfNoTests` prevents a production module with no test suite from passing without generating JaCoCo execution data. |
| JaCoCo | JaCoCo Maven Plugin 0.8.15 | Line coverage minimum 71% at bundle, each production class, and each concrete method. At method granularity this means more than 70% of that method's executable lines must be exercised; it is not a semantic measure of test quality. Test classes and conventional `**/generated/**` namespace are excluded. Test-only modules without production classes are not production coverage bundles. Do not exclude handwritten production code. |
| Architecture tests | ArchUnit and artifact inspection | Enforce module dependency direction, keep Lending Core free of concrete provider imports, and prove concrete providers are absent from the host runtime distribution. Real plugin JARs/Testcontainers remain required evidence for architectural claims. |
| Design review | `docs/quality/architecture-review-checklist.md` | Required manual evidence/traceability review for substantial cross-boundary changes; passing Maven alone is insufficient. |

Maven reports are available under each module's `target/site` and `target` directories after a populated build. JaCoCo XML/HTML reports must be reviewed alongside the gate result; a passing aggregate cannot excuse an under-covered class or method. Selected PMD rules are a practical, blocking smell baseline, not an assertion that static analysis can detect every design smell; the architecture/design review remains mandatory.

## Coding-Agent Review Protocol

Before coding, read `AGENTS.md`, `docs/architecture/01-platform-spec.md`, applicable ADRs, and the user-approved scope. Before completion:

1. Review the actual diff against requirements, domain invariants, accepted ADRs, and scope boundaries.
2. Record important claim -> module/class -> test -> documentation links in the review checklist.
3. Run the blocking build command under JDK 21 and report its exact result; separately identify required integration checks that were unavailable.
4. Complete the architecture/design checklist. Report findings and unresolved gaps before the change summary.
5. Update architecture documents when implementation differs from the plan. Do not silently implement an unapproved invariant or boundary change.

## Coverage Scope

The root reactor aggregator contains no Java sources and `integration-tests` is test-only, so those modules have no production classes for SpotBugs/PMD/JaCoCo to analyze. Surefire still runs the architecture/package tests in `integration-tests`. This does not exempt production modules: SpotBugs and PMD run in each, Surefire's `failIfNoTests` blocks untested modules, and JaCoCo checks module bundle/class/method coverage. Historical fixtures under `m0-spikes/` are not product modules and are excluded from product coverage thresholds.
