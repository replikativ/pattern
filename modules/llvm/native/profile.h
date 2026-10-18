#ifndef PATTERN_LLVM_PROFILE_H
#define PATTERN_LLVM_PROFILE_H

#include "llvm/Config/llvm-config.h"
#include "llvm/IR/Instruction.h"
#include "llvm/Support/JSON.h"
#include <vector>

namespace pattern_llvm_profile {
using namespace llvm;
inline StringRef editPolicy(unsigned opcode) {
  switch (opcode) {
  case Instruction::Add: case Instruction::Sub: case Instruction::Mul:
  case Instruction::And: case Instruction::Or: case Instruction::Xor:
  case Instruction::Shl: case Instruction::LShr: case Instruction::AShr:
  case Instruction::UDiv: case Instruction::SDiv: case Instruction::URem: case Instruction::SRem:
    return "integer-binop";
  case Instruction::Ret: return "return-value";
  default: return "none";
  }
}
inline bool reserved(unsigned opcode) {
  return opcode == Instruction::UserOp1 || opcode == Instruction::UserOp2;
}
inline StringRef family(unsigned opcode) {
  if (reserved(opcode)) return "internal-reserved";
  if (Instruction::isTerminator(opcode)) return "terminator";
  if (Instruction::isBinaryOp(opcode)) return "binary";
  if (Instruction::isCast(opcode)) return "cast";
  switch (opcode) {
  case Instruction::FNeg: return "unary";
  case Instruction::Alloca: case Instruction::Load: case Instruction::Store:
  case Instruction::GetElementPtr: case Instruction::Fence:
  case Instruction::AtomicCmpXchg: case Instruction::AtomicRMW: return "memory";
  case Instruction::CleanupPad: case Instruction::CatchPad: return "funclet-pad";
  case Instruction::Call: return "call";
  case Instruction::ICmp: case Instruction::FCmp: return "compare";
  case Instruction::ExtractElement: case Instruction::InsertElement:
  case Instruction::ShuffleVector: return "vector";
  case Instruction::ExtractValue: case Instruction::InsertValue: return "aggregate";
  default: return "other";
  }
}
inline StringRef opcodeName(unsigned opcode) {
  if (opcode == Instruction::UserOp1) return "userop1";
  if (opcode == Instruction::UserOp2) return "userop2";
  return Instruction::getOpcodeName(opcode);
}
inline json::Array fields(unsigned opcode) {
  switch (opcode) {
  case Instruction::Call:
    return json::Array{"callee", "arguments", "function-type", "calling-convention", "attributes",
            "attribute-records", "operand-bundles", "callee-slot", "argument-slots", "tail-kind"};
  case Instruction::Invoke: case Instruction::CallBr:
    return json::Array{"callee", "arguments", "function-type", "calling-convention", "attributes",
            "attribute-records", "operand-bundles", "callee-slot", "argument-slots"};
  case Instruction::GetElementPtr:
    return json::Array{"source-element-type", "result-element-type", "indices", "pointer-slot", "index-slots", "inbounds", "nusw", "nuw"};
  case Instruction::Alloca: return json::Array{"allocated-type", "alignment", "address-space"};
  case Instruction::Load: case Instruction::Store:
    return json::Array{"volatile", "alignment", "ordering", "ordering-name", "sync-scope", "sync-scope-name"};
  case Instruction::Fence: return json::Array{"ordering", "ordering-name", "sync-scope", "sync-scope-name"};
  case Instruction::AtomicCmpXchg:
    return json::Array{"volatile", "weak", "alignment", "success-ordering", "success-ordering-name",
            "failure-ordering", "failure-ordering-name", "sync-scope", "sync-scope-name"};
  case Instruction::AtomicRMW:
    return json::Array{"volatile", "alignment", "operation", "operation-name", "ordering", "ordering-name",
            "sync-scope", "sync-scope-name"};
  case Instruction::ICmp: return json::Array{"predicate", "same-sign"};
  case Instruction::FCmp: return json::Array{"predicate"};
  case Instruction::Trunc: case Instruction::Add: case Instruction::Sub:
  case Instruction::Mul: case Instruction::Shl: return json::Array{"nuw", "nsw"};
  case Instruction::UDiv: case Instruction::SDiv:
  case Instruction::LShr: case Instruction::AShr: return json::Array{"exact"};
  case Instruction::Or: return json::Array{"disjoint"};
  case Instruction::ZExt: case Instruction::UIToFP: return json::Array{"nonneg"};
  case Instruction::PHI: return json::Array{"incoming"};
  case Instruction::Switch: return json::Array{"condition", "default", "cases"};
  case Instruction::Br: return json::Array{"conditional"};
  case Instruction::ExtractValue: case Instruction::InsertValue: return json::Array{"indices"};
  case Instruction::ShuffleVector: return json::Array{"mask"};
  case Instruction::LandingPad: return json::Array{"cleanup", "clauses"};
  case Instruction::CleanupPad: case Instruction::CatchPad: return json::Array{"parent-pad", "arguments"};
  case Instruction::CatchSwitch: return json::Array{"parent-pad", "handlers", "unwinds-to-caller"};
  case Instruction::CleanupRet: return json::Array{"cleanup-pad", "unwinds-to-caller"};
  case Instruction::CatchRet: return json::Array{"catch-pad"};
  default: return json::Array{};
  }
}
inline json::Array conditionalFields(unsigned opcode) {
  switch (opcode) {
  case Instruction::FNeg: case Instruction::FAdd: case Instruction::FSub:
  case Instruction::FMul: case Instruction::FDiv: case Instruction::FRem:
  case Instruction::FPTrunc: case Instruction::FPExt:
  case Instruction::FCmp: case Instruction::PHI: case Instruction::Select:
  case Instruction::Call:
    return json::Array{"fast-math"};
  case Instruction::Br: return json::Array{"condition"};
  case Instruction::CatchSwitch: case Instruction::CleanupRet: return json::Array{"unwind-destination"};
  default: return json::Array{};
  }
}
inline json::Object capabilities() {
  json::Array opcodes;
#define HANDLE_INST(number, opcode, instructionClass) \
  { auto extra = fields(number); auto conditional = conditionalFields(number); \
    opcodes.push_back(json::Object{{"id", number}, {"opcode", opcodeName(number)}, \
      {"family", family(number)}, {"serializable", !reserved(number)}, \
      {"projection", reserved(number) ? "unsupported" : \
        (extra.empty() && conditional.empty() ? "structural" : "operation-fields")}, \
      {"fields", std::move(extra)}, {"conditional-fields", std::move(conditional)}, \
      {"operand-edit-policy", editPolicy(number)}}); }
#include "llvm/IR/Instruction.def"
  return json::Object{
    {"format", "pattern.llvm.capabilities/2"}, {"profile-id", "llvm21-persistent-ir/2"},
    {"llvm-version", LLVM_VERSION_STRING}, {"wire-format", "pattern.llvm/2"},
    {"graph-format", "pattern.llvm.graph/2"}, {"edits-format", "pattern.llvm.edits/2"},
    {"snapshot-format", "pattern.llvm.snapshot/2"}, {"opcodes", std::move(opcodes)},
    {"common-node-fields", json::Array{"id", "kind", "type", "operands", "ir"}},
    {"common-instruction-fields", json::Array{"opcode", "parent", "successors", "metadata-attachments", "debug-records"}},
    {"string-convention", "UTF8 field or field-bytes-hex; identifier presentation only"},
    {"node-projections", json::Object{
      {"function", json::Array{"arguments", "blocks", "attributes", "attribute-records", "calling-convention", "declaration"}},
      {"argument", json::Array{"parent", "index"}}, {"block", json::Array{"parent", "instructions"}},
      {"global-value", json::Array{"value-type", "linkage", "visibility", "address-space", "unnamed-address", "dll-storage", "dso-local"}},
      {"global", json::Array{"initializer", "constant", "thread-local", "thread-local-mode"}},
      {"alias", json::Array{"aliasee"}}, {"ifunc", json::Array{"resolver"}},
      {"block-address", json::Array{"function", "block"}},
      {"constant-array", json::Array{}}, {"constant-struct", json::Array{}}, {"constant-vector", json::Array{}},
      {"constant-data-array", json::Array{"element-type", "element-bits", "count", "encoding", "data-hex"}},
      {"constant-data-vector", json::Array{"element-type", "element-bits", "count", "encoding", "data-hex"}},
      {"constant-int", json::Array{"value", "unsigned-value", "bits", "bits-hex"}},
      {"constant-float", json::Array{"bits", "bits-hex", "semantics"}},
      {"constant-expr", json::Array{"opcode", "operator-raw-flags", "nuw", "nsw", "exact", "source-element-type", "result-element-type", "inbounds", "nusw"}},
      {"inline-asm", json::Array{"asm", "constraints", "dialect", "side-effects", "align-stack", "can-throw", "function-type"}}}},
    {"type-projections", json::Object{
      {"common", json::Array{"id", "kind", "ir", "elements"}},
      {"integer", json::Array{"bits"}}, {"pointer", json::Array{"address-space"}},
      {"array", json::Array{"count"}}, {"vector", json::Array{"count", "scalable"}},
      {"struct", json::Array{"packed", "opaque", "name"}}, {"function", json::Array{"varargs"}},
      {"float", json::Array{"bits", "semantics"}},
      {"target-ext", json::Array{"name", "type-parameters", "integer-parameters"}}}},
    {"module-projection-fields", json::Array{"source-filename", "target-triple", "data-layout", "functions", "globals", "aliases", "ifuncs", "named-metadata"}},
    {"common-conditional-fields", json::Array{"name", "name-bytes-hex"}},
    {"global-object-fields", json::Array{"metadata-attachments", "section", "alignment", "comdat"}},
    {"metadata-projection", "opaque attachment/named-metadata/debug-record text inventory; no typed graph"},
    {"record-projections", json::Object{
      {"comdat", json::Array{"name", "selection-kind"}},
      {"operand-bundle", json::Array{"tag", "inputs", "input-slots"}},
      {"phi-incoming", json::Array{"value", "block"}},
      {"switch-case", json::Array{"value", "block", "value-slot", "block-slot"}},
      {"metadata-attachment", json::Array{"kind-id", "kind-name", "ir"}},
      {"named-metadata", json::Array{"name", "ir"}},
      {"attribute-position", json::Array{"position", "index", "attributes"}},
      {"attribute", json::Array{"kind", "ir", "name", "value", "type", "range", "ranges"}},
      {"constant-range", json::Array{"bits", "lower-bits-hex", "upper-bits-hex", "full", "empty"}},
      {"fast-math", json::Array{"reassoc", "nnan", "ninf", "nsz", "arcp", "contract", "afn"}},
      {"landingpad-clause", json::Array{"kind", "value"}}}},
    {"attribute-kinds", json::Array{"enum", "int", "type", "string", "range", "range-list", "opaque"}},
    {"limitations", json::Array{"metadata/debug semantics remain in assembly capsule; opaque text inventories exposed", "inspection is not semantic completeness",
      "nested constant IR fallback can expand aggregates", "rare generic constants retain ordered User operands and opaque IR fallback", "operand editing restricted to declared policies and verifier"}}};
}
} // namespace pattern_llvm_profile
#endif
