# FinBridge M0 Technical Spike Results

**Run date:** 2026-10-26  
**Purpose:** Validate limited architecture premises for M0 only. These spikes are not the product implementation and do not prove M1-M7 behavior.

## Toolchain

- JDK: OpenJDK 21.0.12.1
- Maven Wrapper: 3.9.11 (`only-script` distribution)
- Spring Boot: 3.5.16
- Java enforcement: Maven Enforcer `requireJavaVersion` `[21,22)`

Commands executed:

```bash
JAVA_HOME="${JAVA_21_HOME:?Set JAVA_21_HOME to a JDK 21 installation}" \
PATH="$JAVA_21_HOME/bin:$PATH" \
./mvnw -version

JAVA_HOME="${JAVA_21_HOME:?Set JAVA_21_HOME to a JDK 21 installation}" \
PATH="$JAVA_21_HOME/bin:$PATH" \
./mvnw -f m0-spikes/build/pom.xml clean package
```

**Result:** Wrapper reports Maven 3.9.11 on Java 21.0.12.1. Maven Enforcer passed and the minimal Spring Boot 3.5.16 executable JAR packaged successfully. The isolated build is under `m0-spikes/build`; it is not a host module or production application. No test sources exist in this package-only compatibility spike.

## Real SPI JAR and Dependency Isolation

Command:

```bash
JAVA_HOME="${JAVA_21_HOME:?Set JAVA_21_HOME to a JDK 21 installation}" \
PATH="$JAVA_21_HOME/bin:$PATH" \
bash m0-spikes/classloader/run.sh
```

The script compiles the shared API and host into one classpath, then builds two helper libraries with the same class name `spike.privateapi.Helper` but different behavior. It packages each helper with a provider and `META-INF/services` descriptor into separate external plugin JARs. Plugin JARs are not on the host classpath. A per-plugin child-first loader delegates `spike.api.*` to its parent and defines plugin/private classes itself. Generated classes and JARs are created under a temporary directory and removed at exit.

Observed output (loader identity values are run-specific):

```text
spike.impl.SampleProvider -> helper-v1 | SPI loader=jdk.internal.loader.ClassLoaders$AppClassLoader@... | helper loader=spike.host.ChildFirstPluginClassLoader@... | marker=helper-v1
spike.impl.SampleProvider -> helper-v2 | SPI loader=jdk.internal.loader.ClassLoaders$AppClassLoader@... | helper loader=spike.host.ChildFirstPluginClassLoader@... | marker=helper-v2
PASS: external SPI discovery and private dependency isolation
```

Assertions passed: each external JAR was discovered using `ServiceLoader`; each provider executed its own private helper version; provider implementation classes were defined by their plugin loader; both implemented the parent-loaded shared SPI class; helper classes had distinct `Class` identities and defining plugin loaders.

**Proves:** The basic Java class identity and controlled delegation premise for real compiled artifacts.  
**Does not prove:** Artifact security, malformed descriptor rejection, production runtime lifecycle, hot-install API behavior, safe unload/GC, thread cleanup, concurrent draining, or payment/workflow correctness.

## Repayment Calculation Spike

Command:

```bash
JAVA_HOME="${JAVA_21_HOME:?Set JAVA_21_HOME to a JDK 21 installation}" \
PATH="$JAVA_21_HOME/bin:$PATH" \
bash m0-spikes/repayment/run.sh
```

Observed output:

```text
PASS: zero-rate and amortizing schedules reconcile principal
PASS: deterministic output and anchored month-end due dates
12-month CNY 10,000 at 12%: payment=888.49, totalInterest=661.86
```

The standalone Java 21 calculation uses `BigDecimal`, `DECIMAL128` intermediate arithmetic, two-place `HALF_UP` monetary rounding, and final-installment principal adjustment. Checks cover zero-rate division, principal reconciliation, non-negative balances, deterministic results, and Jan 31 month-end anchoring including leap year.

**Proves:** Representative calculations under the proposed demo convention.  
**Does not prove:** That this is suitable for a real product, every supported amount/term, every currency, or a complete M2 property-test suite. Final scale, rounding, and due-date policy remain open for review.

## Not Run

- PostgreSQL/Testcontainers persistence and crash-recovery feasibility.
- Spring host startup/API behavior, project Docker Compose stack, or frontend build. (The Docker daemon itself is available.)
- Malformed/protected plugin rejection, lifecycle/draining, routing races, or unload cleanup.
- Full supported-term/amount calculation property tests.
- Any end-to-end loan origination, risk decision, payment, reconciliation, or tenant authorization test.

Those features remain unimplemented and unverified. M1-M7 remain gated on review and explicit approval of the M0 proposal.
