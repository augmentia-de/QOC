# Data ownership – which table belongs to which bounded context

> **Purpose:** The one question an event log does not answer by itself:
> **Who may write this table?**
>
> **Basis:** [`README.md`](./README.md) §1 (aggregate principle) and §10
> (nine transactions), [`../java-packages.md`](../java-packages.md) §7
> (layer rules), [`../module-plan.md`](../module-plan.md) §4 (modules).
>
> **Status:** 2026-10-22

## 1. The term

**Data ownership** means: Exactly **one** projection writes a table. Everyone
else reads it through a port. Whoever wants to write publishes an event.

This is not an organizational question but a consequence of the design:

```
Module  --publishes-->  Event  --projects-->  exactly one table
   \                                                     /
    `----------- reads via port -----------------------'
```

**Why it matters:** Without this rule two truths emerge. Two writers on the
same table mean two transactions, two orderings, and a state that depends on
the order – exactly what the log is meant to prevent.

## 2. The seven bounded contexts

| BC | Modules | Writes |
|---|---|---|
| **Protocol** | `ports` (`Journal`), `adapter.sql` | `event`, `event_sequence` |
| **Session** | `session`, `delivery` | `session_v2`, `session_inbox` |
| **History** | `delivery` (projection), `step`, `revert` (events only) | `session_message` |
| **Execution** | `execution` | `session_claim` |
| **Instruction** | `instructions` | `instruction_entry`, `instruction_blob`, `instruction_state` |
| **Rights** | `permissionengine` | `permission` |
| **Project** | **missing** (see §5) | `project`, `worktree` |

The assignment follows the stages from `java-packages.md`, not the folders: a BC
may span several stages (Session uses S0–S2), but it may **not own** a table
that another stage writes.

## 3. Ownership matrix

| Table | Writer (projection) | Triggering events | Reader (port) | BC |
|---|---|---|---|---|
| `event` | `Journal.append` | all durable events | `Journal`, `Conversation` | Protocol |
| `event_sequence` | `Journal.append` | every `append` | `Journal` | Protocol |
| `session_v2` | Projection *session state* | `enqueued`, `step.ended`, `execution.terminal`, `revert.committed` | `SessionReader` | Session |
| `session_inbox` | Projection *delivery* | `inbox.enqueued`, `inbox.delivered`, `inbox.cancelled` | `SessionReader` | Session |
| `session_message` | Projection *history* | `inbox.delivered`, `tool.start/end`, `step.ended`, `compaction.*`, `revert.committed` | `Conversation` | History |
| `session_claim` | `ClaimStore` | `execution.started`, `execution.terminal` | `ClaimStore` | Execution |
| `instruction_entry` | `InstructionStore` | `instructions.updated`, `revert.committed` | `InstructionStore` | Instruction |
| `instruction_blob` | `InstructionStore` | same as `instruction_entry` | `InstructionStore` | Instruction |
| `instruction_state` | `InstructionStore` | `compaction.*`, `revert.committed` | `InstructionStore` | Instruction |
| `permission` | `ApprovalStore` | user action (no event) | `ApprovalStore` | Rights |
| `project` | **no writer** | – | – | Project |
| `worktree` | **no writer** | – | – | Project |

**Two rows with "no writer" are intentional.** They are the finding from §5,
not a typo: a table without an owner is a table without a rule.

## 4. Rules

**H-1** Exactly one projection writes a table. Two writers are a design
error, not a special case.

**H-2** Modules never write directly. They publish an event; the projection
writes it in the same transaction.

**H-3** Reading goes through ports. A join across a BC boundary is forbidden –
even when both tables live in the same database.

**H-4** Foreign keys only **within** a BC. The two exceptions
(`session_v2.project_id`, `session_message.session_id`) are here because they
bind the aggregate, not because they read a foreign truth.

**H-5** One transaction touches one aggregate. The nine transactions from
`README.md` §10 are the final list; a tenth needs its own section, not a
silent introduction.

**H-6** Every projection is idempotent and recomputable from the log. A
projection that cannot be rebuilt is a second truth.

**H-7** Every table has an owner. A table without a module is not created (see
`README.md` §8: no table without a writer).

**H-8** Schema changes only with the owner's consent. The migration names them
in its header:

```sql
-- owner: session
ALTER TABLE session_v2 ADD COLUMN ...
```

**H-9** A read model for a foreign BC is a **separate** table or view fed from
events – not a runtime join.

**H-10** A change of ownership happens only via Expand/Contract: first write
twice, then switch reads, then remove the old path.

## 5. Findings

### B-1 · `project`/`worktree` have no owner

The module plan knows no project module. The tables come from the reference
checkout; their semantics depend on the location question (`L-A` in
[`../session-engine/Java-Impl.md`](../session-engine/Java-Impl.md) §3).

**Proposal:** a module `projects` in S3 with the port `ProjectStore`
(`resolve(path) -> ProjectID`, `worktrees(project)`). Until then the tables are
**not** created – a table without an owner is forbidden under H-7.

### B-2 · `session_v2` has three triggers but one writer

The table is touched in three transactions (admission, step end, terminal).
That is **not** a violation of H-1: it is always written by the same
projection. But it is a question of column ownership:

| Column group | Domain owner | Written from |
|---|---|---|
| `id`, `project_id`, `agent`, `model`, `permission` | Session | Admission |
| `cost`, `tokens_*` | Step | Step end |
| `time_idle`, `idle_outcome`, `time_suspended`, `resume_attempts` | Execution | Terminal |
| `revert` | Revert | Revert commit |

**Rule:** Whoever adds a column needs the consent of its domain owner – even
when the writer is the same. Otherwise the table grows into shared state that
nobody oversees anymore.

### B-3 · `session_message` is history, not session

The table sits physically close to `session_v2`, but belongs to the BC
*History*. Whoever reads it for session metadata violates H-3. This is the most
common confusion, because both are named `session_`.

## 6. Verification procedure

Three checks, from cheap to expensive:

| Check | How | Finds |
|---|---|---|
| **Writer count** | count, per table, how many classes contain `INSERT INTO`/`UPDATE` | two writers (H-1) |
| **SQL boundary** | ArchUnit `G-3` (no `java.sql` outside `adapter`) | access from outside (H-3) |
| **Join boundary** | search for `JOIN` across BC tables in the adapter | forbidden joins (H-3) |

**The writer count is the most important check**, because it stands in one
line of code and still finds everything:

```bash
for t in event session_v2 session_message session_inbox session_claim \
         instruction_entry instruction_blob instruction_state permission; do
  echo "$t: $(grep -rlE "(INSERT INTO|UPDATE)[[:space:]]+$t" src/main/java | wc -l) writers"
done
```

The result must be `0` or `1` everywhere. `0` means: table is not created.
`1` is the normal case. `2` is a finding.

## 7. Change of ownership

When a table changes BC, the same mechanics apply as for any data migration
(see `../module-plan.md` §4, stage S8):

1. **Expand:** the new projection also writes, the old one stays leading.
2. **Compare:** sample and fully verify that both produce the same content.
3. **Contract:** switch readers to the new source, disable the old projection.
4. **Way back:** as long as both write, the way back is a switch, not data
   loss.

**Rule:** A change of ownership without a comparison phase is data loss with
advance notice.

## 8. Invariants

| # | Invariant | Check |
|---|---|---|
| H-1 | One table, one writer | writer count per table |
| H-2 | No module writes directly | ArchUnit `G-3` + review |
| H-3 | No joins across BC boundaries | adapter review |
| H-4 | Foreign keys only within a BC | schema review |
| H-5 | One transaction, one aggregate | list of the nine transactions |
| H-6 | Projections are recomputable | rebuild test |
| H-7 | No table without an owner | ownership matrix |
| H-8 | Migration names the owner | migration header |
| H-9 | Foreign read models are separate tables | schema review |
| H-10 | Change of ownership only via Expand/Contract | migration plan |

> **The benefit is not order, but reversibility.** As long as every table has
> exactly one writer, every BC can be extracted individually – and that is the
> whole point of the refactoring.