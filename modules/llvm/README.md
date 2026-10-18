# Optional LLVM data adapter

This experimental module imports LLVM 21 assembly (`.ll`) and bitcode (`.bc`)
through LLVM's own parser and exposes a persistent value/type graph to Pattern.
The versioned support profile is **`llvm21-persistent-ir/2`**. The locally tested
native version is **21.1.2**; this optional module requires
JDK 17 or later. Pattern's normal source paths,
dependencies and matcher implementation do not load this module.

```mermaid
flowchart LR
  Input[LLVM assembly or bitcode] --> Parse[Optional LLVM 21 subprocess]
  Parse --> Graph[Persistent ID tables and ordered operand slots]
  Graph --> Match[Pattern rules over node records]
  Match --> Branch[Independent edited snapshots]
  Branch --> Patch[Source and graph bound edit log]
  Patch --> Verify[Fresh LLVM parse and verification]
  Verify --> Output[Exclusively published LLVM assembly]
```

## Data and persistence

`pattern.llvm/import-file` returns ordinary Clojure data:

```clojure
{:format "pattern.llvm.snapshot/2"
 :id "..." :parent "..."
 :llvm-version "21.1.2"
 :coverage {:assembly-capsule true :typed-metadata false
            :capabilities {:profile-id "llvm21-persistent-ir/2" ...} ...}
 :module {:functions [1 ...] :globals [...] ...}
 :nodes {id {:id id :kind "instruction" :opcode "add" :type type-id
             :parent block-id :operands [value-id value-id]
             :original-ir "..." ...}}
 :types {type-id {:id type-id :kind "integer" :bits 32 :elements [] ...}}
 :uses {value-id #{[user-id operand-index] ...}}
 :predecessors {block-id #{[terminator-id successor-index] ...}}
 :edits [{:user id :slot 0 :from value-id :to other-value-id} ...]
 :origin {:assembly "..." :sha256 "..." :graph-sha256 "..." :base {...} ...}}
```

The ellipses above indicate omitted fields, not literal data to read as a snapshot.
Names are presentation. IDs are positive signed 64-bit integers scoped to the
imported module lineage. Qualify baseline references with LLVM version, source
SHA256, graph SHA256 and local ID when identifying values across modules;
a bare integer is not a global identity. Use `[snapshot-id local-id]` for a
particular edited node version. Reimporting an edited module establishes a new
baseline; general ID correspondence across reimports is not promised.

Operand vectors retain order and duplicate occurrences. PHIs additionally expose
their ordered `incoming` value/block pairs; terminators expose ordered `successors`.
Containment, raw LLVM operand uses and control-flow edges remain distinct. Cycles
and forward references use IDs, so traversal does not recursively expand loops.
Pattern matches a selected node's map; tree walkers should not implicitly follow
the entire reference graph.

`graph/fork`, `graph/set-operand`, `graph/replace-uses` and `graph/rewrite-node`
preserve previous snapshots. Multi-slot edit transactions name the input snapshot
as their parent; unpublished intermediate roots remain private.
An edit shares unaffected node payloads and persistent table paths. The baseline
assembly and original node/type tables are shared across the lineage. Reverse-use
indexes are updated along with operands; a transaction that throws publishes no
new snapshot. `original-ir` always describes the baseline, not reconstructed text
after an edit. Export replays the recorded changes and rejects unrecorded edits
to node fields, type tables, module semantics or derived indexes.

This is persistence of compiler data in memory. A durable content-addressed store,
cross-process snapshot format, execution checkpoint or transactional host effects
are not implemented here. A future durable store can keep the baseline once and
persist node chunks and edit lineage separately; serializing full maps repeatedly
would lose the memory model's sharing on disk.

## Explicit support contract

`llvm/capabilities` queries the selected native executable. The returned declaration
must match the checked-in [profile manifest](src/pattern/llvm/profile.edn), except
for the separately checked LLVM 21 patch version. It classifies **all 67 LLVM 21
instruction enum slots**: 65 serializable opcodes and two reserved internal slots
(`userop1`/`userop2`) that cannot be imported as instructions. Every row names its
family, mandatory and conditional fields, and operand-edit policy. Classification
is not a claim that every semantic facet has a typed projection.

`support/support-report` distinguishes this declared inventory from opcodes actually
encountered in a snapshot. `support/node-support` reports actual editable operand
slots and known metadata/debug inventories. `support/require-support!` preflights
explicit node IDs and required fields before a pass. Unknown requirement keys,
missing fields and unsupported edit policies fail. A request for no metadata or
debug records requires a known, empty inventory; missing data is never treated as
proof of absence. These checks establish data availability, not rewrite correctness.

| Data | Structured access | Editing/export |
| --- | --- | --- |
| Functions, arguments, blocks, instructions | IDs, containment, opcode, types, ordered operands; all 65 serializable opcodes classified | Existing operands of admitted instructions only |
| CFG, PHIs and exception handling | Successor slots, reverse edges, incoming pairs, switch cases, landingpad/funclet roles | CFG, PHI and EH editing rejected |
| Calls | Callee, arguments, actual operand slots, bundles and their slots, function type, calling convention, tail kind, typed attribute variants | Call/attribute/bundle editing rejected |
| Memory and arithmetic | GEP source/result types and nowrap flags; alloca fields; atomic operation/order/syncscope names; wrap/exact/disjoint/nonneg/samesign and applicable fast-math flags | Only admitted operand slots; flags preserved |
| Constants | Exact integer/FP bits; widths and FP semantics; compact little-endian constant-data arrays/vectors; constant-expression fields; inline assembly | Creating or changing constants rejected |
| Types and globals | Type components, scalable vectors, target extension parameters; global initializer/TLS/linkage/alignment/comdat and alias/ifunc roles | Construction and global/type edits rejected |
| Attributes | Position and tagged enum/int/type/string/range/range-list records; explicit opaque fallback | Preserved; mutation rejected |
| Metadata/debug | Opaque attachment inventory with kind IDs/names and text; named metadata and debug record text; complete assembly capsule | No typed metadata graph or edits |
| Remaining semantic facets | Canonical assembly and available fallback text | Preserved by baseline parsing, not reconstructed from records |

The manifest's node/type/record projection lists describe available facets,
including conditional fields; opcode `fields` and common instruction fields are
mandatory on each encountered instruction. Names use the documented UTF-8 or
`*-bytes-hex` alternative. In contrast, pass requirements select exact keys: a
pass requesting a decoded string must explicitly handle its byte alternative.
Examples of remaining opaque facets include constant-expression GEP `inrange`,
some rare constants, and metadata relationships. Full reconstruction of arbitrary
LLVM IR from graph maps is outside this profile. `/1` wire data and snapshots are
rejected; reimport the original `.ll`/`.bc` to establish a `/2` baseline.

The editable opcodes are integer scalar/vector `add`, `sub`, `mul`, `and`, `or`,
`xor`, shifts and signed/unsigned division/remainder, plus a return's existing
operand. Replacements must be existing values of the same LLVM type. LLVM then
checks the whole resulting module, including dominance. **Verification establishes
LLVM validity, not semantic equivalence.** Pattern rules must account for poison,
undef, overflow flags, fast-math, effects and other relevant semantics before
claiming to be optimizations.

The native importer parses the input, prints a complete canonical capsule, drops
the original context, then reparses that exact capsule in a fresh context before
assigning IDs. It rejects an unstable parse/print boundary. Source SHA256, a
canonical graph/header/coverage digest and the exact LLVM version bind subsequent
edits to that baseline. Ordered repeated edits check each preceding operand value.
Unmodeled fields stay in the capsule instead of disappearing from output.

Valid UTF-8 names remain strings. Arbitrary byte names/header strings use explicit
`*-bytes-hex` fields. Integer constants retain signed/unsigned decimal strings and exact bit hex with
a separate width; FP constants retain bit hex and semantics. Neither passes
through floating-point JSON numbers. Constant-data payloads are explicitly packed
little-endian element bits, independent of host or target endianness. Container fallback text is
compact, avoiding repeated function/block bodies. Nested constant fallback text
can still expand aggregate contents; large-module memory work remains necessary.

## Build and try

From the Pattern repository root, with LLVM 21 development headers/libraries:

```sh
LLVM_CONFIG=llvm-config-21 CXX=clang++-21 python3 modules/llvm/native/build.py
clojure -J-Xmx512m -M:llvm
```

The helper compiles one small executable against installed LLVM; it does not build
LLVM. A failed build preserves the previous executable. Select the executable
explicitly in application code; nothing downloads or loads LLVM on namespace load.

```clojure
(require '[pattern :as pattern]
         '[pattern.llvm :as llvm]
         '[pattern.llvm.graph :as graph]
         '[pattern.llvm.support :as support]
         '[pattern.r3.core :refer [rule]])

(def bridge "./modules/llvm/target/bridge")
(llvm/capabilities bridge)
(def base (llvm/import-file bridge "modules/llvm/test/fixtures/graph.ll"))
(support/support-report base)
(def add? (pattern/compile-pattern
           '{:kind "instruction" :opcode "add" :operands [?left ?right]}))
(keep add? (vals (:nodes base)))

;; Demonstration on this known fixture: retarget the identity instruction's
;; users to the answer instruction. The original snapshot remains available.
(defn named [snapshot name]
  (first (filter #(= name (:name %)) (vals (:nodes snapshot)))))
(def child (graph/replace-uses base (:id (named base "identity"))
                                  (:id (named base "answer"))))
(llvm/export-file! bridge child "./rewritten.ll") ; destination must not exist
```

A Pattern rule can return a node map directly. The adapter accepts only an
operand-vector difference and records its changes as one transaction:

```clojure
;; `pattern` above registers Pattern's map matcher. No native calls occur in rules.
(def swap-add
  (rule '(?:as node {:kind "instruction" :opcode "add" :operands [?a ?b]})
        (assoc node :operands [b a])))
(def answer-id (:id (named base "answer")))
(support/require-support! base {:nodes [answer-id] :operation :set-operand
                              :fields [:nuw :nsw] :metadata :none :debug-records :none})
(def swapped (graph/rewrite-node base answer-id swap-add))
(llvm/export-file! bridge swapped "./swapped.ll")
```

The rule is responsible for semantic justification. A no-match returns the
original snapshot; changing other node fields or adding/removing operand slots
fails. External effects performed by a rule are not rolled back.

Export uses an isolated native context, verifies before publication, then links a
fully written sibling temporary file exclusively into the destination. It does
not overwrite existing files or claim fsync durability. Abrupt process termination
may leave a sibling temporary file; a partial final file is not published.

The subprocess defaults to 30 seconds and 128 MiB of combined stdout/stderr.
Capability, import and export calls accept an options map with `:timeout-ms` (1..86400000) and
`:max-output-bytes`. These limits bound subprocess time/output, not total JVM
parser memory or final LLVM file size. Import/export do not execute input code;
integration tests separately execute only their known fixture through `lli`.

The module also has its own `deps.edn` for source consumers using a local root or
Git `:deps/root "modules/llvm"`. No new Maven artifact is published by this work.

## Optional Bifurcan tables

```sh
clojure -J-Xmx512m -M:llvm:llvm-bifurcan
```

```clojure
(require '[pattern.llvm.bifurcan :as bifurcan])
(def alternate (bifurcan/with-backend base))
(graph/replace-uses alternate (:id (named alternate "identity"))
                               (:id (named alternate "answer")))
```

This selects published Bifurcan `0.2.0-rc1` integer maps behind normal Clojure map
interfaces. Node records remain plain Clojure maps. Only an exclusively owned
construction phase uses a linear builder; every published table is forked and
subsequent edits are persistent. The first adapter uses proxy dispatch, whose
cost should be measured before choosing it for a workload.

Bifurcan's `DirectedGraph` stores one edge value per vertex pair, so it is not the
primary LLVM representation: duplicate operand sites and parallel CFG edges must
survive. Its graph algorithms can operate on derived topology or reified edge
vertices later. Default Clojure maps keep the base integration small and useful.

## Checks and comparison

```sh
LLVM_CONFIG=llvm-config-21 clojure -J-Xmx512m -M:llvm:llvm-test
LLVM_CONFIG=llvm-config-21 clojure -J-Xmx512m -M:llvm:llvm-bifurcan:llvm-test bifurcan
clojure -J-Xmx256m -M:llvm:llvm-bifurcan -m pattern.llvm.benchmark '{:rounds 2}'
clojure -J-Xmx512m -M:test-ci
```

The synthetic comparison retains 100 forks of 10,000 node records, updates reverse
uses, performs a 1,000-edit batch, reads values and iterates tables. It checks exact
results, duplicate use positions and old roots outside timing. It includes the
actual wrapper overhead. These wall times have no warmup/statistical controls,
exclude native parsing/checking and cannot establish a general performance winner.
The [initial observations](measurements/initial-observation.json) describe the
historical `/1` prototype, with its own pinned source hashes; they are not `/2`
acceptance or a current performance claim.

The opt-in [LLVM CI workflow](../../.github/workflows/llvm.yml) builds against
signed LLVM 21 packages, runs default and optional Bifurcan suites, checks a fresh
external `:local/root` consumer, and builds the normal Pattern JAR to verify that
LLVM code and optional dependencies remain excluded. Run it locally with:

```sh
LLVM_CONFIG=llvm-config-21 CXX=clang++-21 python3 modules/llvm/scripts/ci.py
```

The receipt records actual versions and completed checks. CI tracks major 21
packages; it does not promise a bit-reproducible 21.1.2 native binary. Native builds
currently use `-O0`, and the integration is a source module in the repository, not
a published standalone Maven artifact. MIR, MLIR and pass-time adapters below
remain future work and have no current import/export support.

Current [profile `/2` acceptance](measurements/profile-v2-observation.json) records
23 tests / 234 assertions by default, 25 / 253 with Bifurcan, and the core regression
suite's 88 / 622, all passing. It pins runtime/test source hashes and local artifacts,
including checks that fixtures encounter all 65 serializable opcodes. The complete
local acceptance job peaked at approximately 644 MiB process-group RSS; this is
not a throughput or large-module memory claim.

## Extending access across LLVM's representations

1. **LLVM IR:** extend remaining opaque facets and typed metadata/debug graphs,
   refine operation-specific schemas, and validate additional real-world corpora. Admit construction,
   deletion and CFG rewrites only with complete field preservation and appropriate
   validation. Add compact/streaming transport after measuring larger modules.
2. **MIR/GlobalISel:** add a separate versioned LLVM CodeGen adapter for machine
   functions, virtual/physical registers, operand roles, register banks, memory
   operands and target information. MIR serialization has its own documented
   limitations; LLVM IR support does not establish machine-IR coverage.
3. **MLIR:** add an adapter using MLIR's own parser/generic operation form and
   bytecode APIs. Preserve dialect-qualified operations/types/attributes, regions,
   block arguments and symbol scopes; distinguish registered and opaque dialects.
4. **Pass-time representations:** expose phase-labelled SelectionDAG and VPlan
   snapshots through LLVM C++ hooks. SelectionDAG edges must retain the selected
   result number as well as their data/control role; its DAG is destroyed after
   instruction selection. VPlan needs its recipes, value definitions and nested
   regions. These adapters require access during the relevant compiler phase,
   rather than assuming the LLVM assembly reader supplies them.
5. **Backend/runtime options:** native pass invocation, code generation or ORC JIT
   can consume verified output independently of the persistent data layer.

Each adapter can reuse the persistent ID-table/use-site approach and Pattern's
node matching. Their schemas and fidelity profiles should remain explicit instead
of flattening distinct semantics into one presumed universal instruction set.
LLVM stays an optional route alongside JVM compilation and other backends.

References: [LLVM IR](https://llvm.org/docs/LangRef.html),
[LLVM bitcode](https://llvm.org/docs/BitCodeFormat.html),
[MIR](https://llvm.org/docs/MIRLangRef.html),
[MLIR](https://mlir.llvm.org/docs/LangRef/),
[SelectionDAG](https://llvm.org/docs/CodeGenerator.html#selectiondag-instruction-selection-process),
[VPlan](https://llvm.org/docs/VectorizationPlan.html),
[Bifurcan](https://github.com/lacuna/bifurcan).
