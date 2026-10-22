# Architecture rules in the build – from prescription to gate

> **Purpose:** Wire the layer and module boundaries from
> [`../java-packages.md`](../java-packages.md) §7 so that a violation **breaks
> the build** – not the review.
>
> **Rule:** An architecture rule that does not run in the build is a request.
>
> **Status:** 2026-10-22

## 1. Rule inventory

Nine rules. Three stand verbatim in
[`../java-packages.md`](../java-packages.md) §7 (G-1, G-2, G-3), three are
variants of existing rules (G-4, G-5, G-9), three are new (G-6, G-7, G-8).

**Already present and not repeated here:** `provider_knows_no_domain`,
`tools_without_journal`, `http_only_in_adapter`, `time_only_via_clock`,
`no_impl_imports` (`java-packages.md` §7.2).

| # | Rule | Tool | Breaks |
|---|---|---|---|
| **G-1** | Nine stages, no edge against the order | ArchUnit `layeredArchitecture` | Build |
| **G-2** | No cycles between packages | ArchUnit `slices` | Build |
| **G-3** | `java.sql` only in `adapter.*` | ArchUnit | Build |
| **G-4** | Port implementations only in `adapter.*` | ArchUnit | Build |
| **G-5** | `adapter.*` is only used by `wiring` | ArchUnit | Build |
| **G-6** | No standard streams (`System.out`, `printStackTrace`) | ArchUnit `GeneralCodingRules` | Build |
| **G-7** | No generic exceptions (`RuntimeException`, `IllegalStateException`) | ArchUnit `GeneralCodingRules` | Build |
| **G-8** | No `catch (Exception\|Throwable\|Error)` | Checkstyle `IllegalCatch` | Build |
| **G-9** | No type names ending in `*Impl` | ArchUnit | Build |

**Why G-8 is not ArchUnit:** A `catch` block is not a dependency but syntax.
ArchUnit sees only which classes a class *uses* – not which exception it
catches. Checkstyle sees the syntax tree and is the right tool here.

## 2. The new rules as code

```java
@AnalyzeClasses(packages = "de.augmentia.oc.engine")
final class ArchitectureTest {

    @ArchTest
    static final ArchRule g3_sql_only_in_adapter =
            noClasses().that().resideOutsideOfPackage("..engine.adapter..")
                    .should().accessClassesThat()
                    .resideInAnyPackage("java.sql..", "javax.sql..");

    @ArchTest
    static final ArchRule g4_ports_only_in_adapter =
            classes().that().implement(Journal.class)
                    .or().implement(Conversation.class)
                    .or().implement(ClaimStore.class)
                    .or().implement(ApprovalStore.class)
                    .or().implement(InstructionStore.class)
                    .or().implement(FileSystem.class)
                    .or().implement(ProcessRunner.class)
                    .or().implement(HttpTransport.class)
                    .should().resideInAPackage("..engine.adapter..");

    @ArchTest
    static final ArchRule g5_adapter_only_from_wiring =
            noClasses().that().resideOutsideOfPackage("..engine.wiring..")
                    .should().dependOnClassesThat().resideInAPackage("..engine.adapter..");

    @ArchTest
    static final ArchRule g6_no_standard_streams =
            GeneralCodingRules.NO_CLASSES_SHOULD_ACCESS_STANDARD_STREAMS;

    @ArchTest
    static final ArchRule g7_no_generic_exceptions =
            GeneralCodingRules.NO_CLASSES_SHOULD_THROW_GENERIC_EXCEPTIONS;

    @ArchTest
    static final ArchRule g9_no_impl_names =
            noClasses().should().haveSimpleNameEndingWith("Impl");
}
```

```xml
<!-- Checkstyle: G-8, scope src/main/java -->
<module name="IllegalCatch">
  <property name="illegalClassNames"
            value="java.lang.Exception, java.lang.Throwable, java.lang.Error"/>
</module>
```

**Why G-7 and G-8 belong together:** The three error channels from
[`../session-engine/06-errors.md`](../session-engine/06-errors.md) §1 are only
sustainable when nobody throws or catches an arbitrary exception. G-7 forbids
throwing, G-8 forbids catching. One rule alone leaves the back door open.

## 3. Build wiring

```xml
<dependency>
  <groupId>com.tngtech.archunit</groupId>
  <artifactId>archunit-junit5</artifactId>
  <version>1.3.0</version>
  <scope>test</scope>
</dependency>
```

```xml
<plugin>
  <artifactId>maven-surefire-plugin</artifactId>
  <configuration>
    <includes>
      <include>**/*Test.java</include>
      <include>**/*ArchitectureTest.java</include>
    </includes>
  </configuration>
</plugin>
```

Three properties that must be set:

1. **A violation is a test failure.** `@ArchTest` fields fail like any other
   test – the build ends red, without special handling.
2. **An empty rule is an error.** If a package name is wrong, the rule checks
   nothing and is silently green. `failOnEmptyShould` must therefore stay
   active.
3. **The rules run in `verify`, not only locally.** Whoever binds them to the
   developer machine does not have them.

## 4. CI

```yaml
architecture:
  runs-on: ubuntu-latest
  steps:
    - uses: actions/checkout@v4
    - uses: actions/setup-java@v4
      with:
        distribution: temurin
        java-version: "21"
        cache: maven
    - run: mvn -B -q -DskipTests compile
    - run: mvn -B -q -Dtest=ArchitectureTest test
    - run: mvn -B -q checkstyle:check
```

**Order:** architecture first, then unit, then contract. A layer violation
should surface in 40 seconds, not after 12 minutes of integration tests.

**Rule:** The architecture job is a **separate** job, not a step in a
combined job. Only then is it immediately visible in a red build whether the
architecture or the logic broke.

## 5. Exceptions

An architecture rule without an exception process gets disabled after two
weeks. The process has four rules:

| # | Rule |
|---|---|
| 1 | **No `@ArchIgnore`.** Exceptions live in a file, not in code. |
| 2 | **Every exception has an owner and an expiry date.** Without a date it is a silent abolition. |
| 3 | **Existing violations are frozen, not permitted.** `FreezingArchRule` holds the current state; every **new** violation breaks. |
| 4 | **Removal is part of the task.** An exception without a removal ticket is a permanent state. |

```java
@ArchTest
static final ArchRule g1_layers =
        FreezingArchRule.freeze(Architectures.layeredArchitecture() /* … */ );
```

The frozen state lives as a text file in the repository and is seen in review –
not in a cache nobody reads.

## 6. What still needs a review

From [`../java-packages.md`](../java-packages.md) §7.3 four points remain that
no static tool can decide:

| Point | Why not checkable | Who checks |
|---|---|---|
| "Database access only within a transaction" | runtime behavior | integration test A-09 |
| "Tool logs before the side effect" | order | test A-05 |
| "Port is only wired with a real adapter by `wiring`" | construction, not import | review of the composition root |
| "No static mutability" | semantics | review + a property test |

**Rule:** These four stand on a **review checklist**, not on the hope that
someone remembers.

## 7. The pipeline checks itself

**GA-1** A rule that hits nothing is an error (`failOnEmptyShould`).

**GA-2** Every rule has a **canary**: a class in `src/test` that violates it
deliberately, and a test proving the rule fires.

```java
@Test
void g3_catches_a_violation() {
    var forbidden = new ClassFileImporter().importClasses(SqlInCore.class);
    assertThatThrownBy(() -> g3_sql_only_in_adapter.check(forbidden))
            .isInstanceOf(AssertionError.class);
}
```

**GA-3** The architecture job runs on the same commit as the unit tests – no
"catch up later".

**GA-4** If a stage is renamed in the package tree, the rule must be adjusted
in the same commit; otherwise it is empty and green.

> **GA-2 is the most important point.** An ArchUnit suite that has never been
> red proves nothing – it could consist of typos in package names.

## 8. Maintenance

| Trigger | Reaction |
|---|---|
| new stage, new package | add `definedBy`, add canary |
| new exception | entry with owner and date, approval in review |
| rule fires on existing code | freeze, do not disable |
| rule hits nothing anymore | delete or correct package names – never leave standing |

**Every six months:** deliberately break each rule once and check whether the
build goes red. That is the only maintenance that counts.