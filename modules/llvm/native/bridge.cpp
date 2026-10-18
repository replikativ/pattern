// Optional LLVM21 bridge. Module assembly is the semantic capsule; graph nodes
// expose LLVM Value identity and ordered operand edges, not a complete schema.
#include "llvm/Config/llvm-config.h"
#include "llvm/IR/Constants.h"
#include "llvm/IR/DerivedTypes.h"
#include "llvm/IR/InstIterator.h"
#include "llvm/IR/Instructions.h"
#include "llvm/IR/Module.h"
#include "llvm/IR/Operator.h"
#include "llvm/IR/Verifier.h"
#include "llvm/IRReader/IRReader.h"
#include "llvm/Support/FileSystem.h"
#include "llvm/Support/JSON.h"
#include "llvm/Support/MemoryBuffer.h"
#include "llvm/Support/SHA256.h"
#include "llvm/Support/SourceMgr.h"
#include "llvm/Support/raw_ostream.h"
#include <algorithm>
#include <map>
#include <set>
#include <string>
#include <vector>

using namespace llvm;
using ID = int64_t;

static int fail(const Twine &message) {
  errs() << "pattern-llvm: " << message << '\n';
  return 1;
}
static std::string render(const Value &value) {
  std::string text;
  raw_string_ostream stream(text);
  if (isa<GlobalValue>(value) || isa<BasicBlock>(value) || isa<Argument>(value))
    value.printAsOperand(stream, true);
  else
    value.print(stream);
  return text;
}
static std::string renderType(Type *type) {
  std::string text;
  raw_string_ostream stream(text);
  type->print(stream);
  return text;
}
static std::string assembly(const Module &module) {
  std::string text;
  raw_string_ostream stream(text);
  module.print(stream, nullptr, true);
  return text;
}
static std::string hexBytes(StringRef bytes) {
  static const char digits[] = "0123456789abcdef";
  std::string result;
  for (unsigned char byte : bytes) {
    result.push_back(digits[byte >> 4]);
    result.push_back(digits[byte & 15]);
  }
  return result;
}
static void stringField(json::Object &out, StringRef key, StringRef bytes) {
  // LLVM permits arbitrary bytes in names/header strings; JSON requires UTF8.
  // Never replace semantic bytes with U+FFFD. The fallback key is unambiguous.
  if (json::isUTF8(bytes)) out[key] = bytes.str();
  else out[(key + "-bytes-hex").str()] = hexBytes(bytes);
}
static void nameField(json::Object &out, StringRef name) {
  stringField(out, "name", name);
}
static std::string sha256(StringRef bytes) {
  auto digest = SHA256::hash(ArrayRef<uint8_t>(reinterpret_cast<const uint8_t *>(bytes.data()), bytes.size()));
  static const char digits[] = "0123456789abcdef";
  std::string result;
  for (uint8_t byte : digest) {
    result.push_back(digits[byte >> 4]);
    result.push_back(digits[byte & 15]);
  }
  return result;
}
// Graph identity uses JSON object keys sorted lexicographically, array order
// unchanged, and LLVM JSON scalar/string escaping. Module path/assembly are
// deliberately excluded; every exported node/type record participates.
static void canonicalJSON(raw_ostream &out, const json::Value &value) {
  if (const auto *object = value.getAsObject()) {
    std::vector<std::pair<StringRef, const json::Value *>> fields;
    for (const auto &entry : *object) fields.push_back({entry.first, &entry.second});
    std::sort(fields.begin(), fields.end(), [](const auto &a, const auto &b) {
      return a.first < b.first;
    });
    out << '{';
    bool first = true;
    for (const auto &field : fields) {
      if (!first) out << ',';
      first = false;
      out << json::Value(field.first) << ':';
      canonicalJSON(out, *field.second);
    }
    out << '}';
  } else if (const auto *array = value.getAsArray()) {
    out << '[';
    bool first = true;
    for (const auto &element : *array) {
      if (!first) out << ',';
      first = false;
      canonicalJSON(out, element);
    }
    out << ']';
  } else {
    out << value;
  }
}
static json::Object coverage() {
  return json::Object{{"assembly-capsule", true}, {"value-operands", true},
    {"typed-metadata", false}, {"debug-records", "assembly-capsule"},
    {"container-ir", "operand-reference"}, {"constant-ir", "may-expand-nested-aggregates"}};
}
static std::string graphHash(const json::Object &records) {
  std::string bytes;
  raw_string_ostream stream(bytes);
  canonicalJSON(stream, json::Value(json::Object(records)));
  return sha256(bytes);
}
static std::string kind(const Value *value) {
  if (isa<Function>(value)) return "function";
  if (isa<GlobalVariable>(value)) return "global";
  if (isa<GlobalAlias>(value)) return "alias";
  if (isa<GlobalIFunc>(value)) return "ifunc";
  if (isa<Argument>(value)) return "argument";
  if (isa<BasicBlock>(value)) return "block";
  if (isa<Instruction>(value)) return "instruction";
  if (isa<PoisonValue>(value)) return "poison";
  if (isa<UndefValue>(value)) return "undef";
  if (isa<ConstantInt>(value)) return "constant-int";
  if (isa<ConstantFP>(value)) return "constant-float";
  if (isa<ConstantExpr>(value)) return "constant-expr";
  if (isa<ConstantAggregateZero>(value)) return "constant-zero";
  if (isa<ConstantPointerNull>(value)) return "constant-null";
  if (isa<BlockAddress>(value)) return "block-address";
  if (isa<Constant>(value)) return "constant";
  if (isa<MetadataAsValue>(value)) return "metadata-value";
  if (isa<InlineAsm>(value)) return "inline-asm";
  return "value";
}
static std::string typeKind(Type *type) {
  if (type->isVoidTy()) return "void";
  if (type->isIntegerTy()) return "integer";
  if (type->isFloatingPointTy()) return "float";
  if (type->isPointerTy()) return "pointer";
  if (type->isStructTy()) return "struct";
  if (type->isArrayTy()) return "array";
  if (type->isVectorTy()) return "vector";
  if (type->isFunctionTy()) return "function";
  if (type->isLabelTy()) return "label";
  if (type->isMetadataTy()) return "metadata";
  if (type->isTokenTy()) return "token";
  return "type";
}

struct Graph {
  std::map<const Value *, ID> valueIDs;
  std::vector<Value *> values;
  std::map<Type *, ID> typeIDs;
  std::vector<Type *> types;
  ID intern(Value *value) {
    auto found = valueIDs.find(value);
    if (found != valueIDs.end()) return found->second;
    ID id = static_cast<ID>(values.size()) + 1;
    valueIDs[value] = id;
    values.push_back(value);
    return id;
  }
  ID internType(Type *type) {
    auto found = typeIDs.find(type);
    if (found != typeIDs.end()) return found->second;
    ID id = static_cast<ID>(types.size()) + 1;
    typeIDs[type] = id;
    types.push_back(type); // Intern before traversing recursive struct types.
    return id;
  }
  ID id(const Value *value) const { return valueIDs.at(value); }
  ID typeID(Type *type) const { return typeIDs.at(type); }
  explicit Graph(Module &module) {
    for (GlobalValue &value : module.global_values()) intern(&value);
    for (Function &function : module) {
      for (Argument &argument : function.args()) intern(&argument);
      for (BasicBlock &block : function) {
        intern(&block);
        for (Instruction &instruction : block) intern(&instruction);
      }
    }
    // Iterative ordered discovery, independent of names and pointer ordering.
    for (size_t i = 0; i < values.size(); ++i) {
      Value *value = values[i];
      internType(value->getType());
      if (auto *user = dyn_cast<User>(value))
        for (Value *operand : user->operands()) intern(operand);
      if (auto *function = dyn_cast<Function>(value)) internType(function->getFunctionType());
      if (auto *global = dyn_cast<GlobalValue>(value)) internType(global->getValueType());
      if (auto *alloca = dyn_cast<AllocaInst>(value)) internType(alloca->getAllocatedType());
      if (auto *gep = dyn_cast<GetElementPtrInst>(value)) internType(gep->getSourceElementType());
    }
    for (size_t i = 0; i < types.size(); ++i)
      for (Type *child : types[i]->subtypes()) internType(child);
  }
  json::Object node(Value *value) const {
    json::Array operands;
    if (auto *user = dyn_cast<User>(value))
      for (Value *operand : user->operands()) operands.push_back(id(operand));
    json::Object out{{"id", id(value)}, {"kind", kind(value)},
                     {"type", typeID(value->getType())},
                     {"operands", std::move(operands)}, {"ir", render(*value)}};
    if (value->hasName()) nameField(out, value->getName());
    if (auto *global = dyn_cast<GlobalValue>(value)) {
      out["value-type"] = typeID(global->getValueType());
      out["linkage"] = static_cast<int64_t>(global->getLinkage());
      out["visibility"] = static_cast<int64_t>(global->getVisibility());
    }
    if (auto *function = dyn_cast<Function>(value)) {
      json::Array args, blocks;
      for (Argument &argument : function->args()) args.push_back(id(&argument));
      for (BasicBlock &block : *function) blocks.push_back(id(&block));
      out["arguments"] = std::move(args);
      out["blocks"] = std::move(blocks);
      std::string attrs;
      raw_string_ostream stream(attrs);
      function->getAttributes().print(stream);
      out["attributes"] = std::move(attrs);
      out["calling-convention"] = static_cast<int64_t>(function->getCallingConv());
      out["declaration"] = function->isDeclaration();
    }
    if (auto *argument = dyn_cast<Argument>(value)) {
      out["parent"] = id(argument->getParent());
      out["index"] = static_cast<int64_t>(argument->getArgNo());
    }
    if (auto *block = dyn_cast<BasicBlock>(value)) {
      out["parent"] = id(block->getParent());
      json::Array instructions;
      for (Instruction &instruction : *block) instructions.push_back(id(&instruction));
      out["instructions"] = std::move(instructions);
    }
    if (auto *instruction = dyn_cast<Instruction>(value)) {
      out["opcode"] = instruction->getOpcodeName();
      out["parent"] = id(instruction->getParent());
      json::Array successors;
      if (instruction->isTerminator())
        for (unsigned i = 0; i < instruction->getNumSuccessors(); ++i)
          successors.push_back(id(instruction->getSuccessor(i)));
      out["successors"] = std::move(successors);
      if (auto *phi = dyn_cast<PHINode>(instruction)) {
        json::Array incoming;
        for (unsigned i = 0; i < phi->getNumIncomingValues(); ++i)
          incoming.push_back(json::Object{{"value", id(phi->getIncomingValue(i))},
                                          {"block", id(phi->getIncomingBlock(i))}});
        out["incoming"] = std::move(incoming);
      }
      if (auto *cmp = dyn_cast<CmpInst>(instruction))
        out["predicate"] = CmpInst::getPredicateName(cmp->getPredicate()).str();
      if (auto *op = dyn_cast<OverflowingBinaryOperator>(instruction)) {
        out["nuw"] = op->hasNoUnsignedWrap();
        out["nsw"] = op->hasNoSignedWrap();
      }
      if (auto *op = dyn_cast<PossiblyExactOperator>(instruction)) out["exact"] = op->isExact();
      if (auto *load = dyn_cast<LoadInst>(instruction)) {
        out["volatile"] = load->isVolatile();
        out["alignment"] = static_cast<int64_t>(load->getAlign().value());
        out["ordering"] = static_cast<int64_t>(load->getOrdering());
        out["sync-scope"] = static_cast<int64_t>(load->getSyncScopeID());
      }
      if (auto *store = dyn_cast<StoreInst>(instruction)) {
        out["volatile"] = store->isVolatile();
        out["alignment"] = static_cast<int64_t>(store->getAlign().value());
        out["ordering"] = static_cast<int64_t>(store->getOrdering());
        out["sync-scope"] = static_cast<int64_t>(store->getSyncScopeID());
      }
    }
    if (auto *integer = dyn_cast<ConstantInt>(value)) {
      SmallString<64> decimal;
      integer->getValue().toString(decimal, 10, true);
      out["value"] = decimal.str().str();
    }
    return out;
  }
  json::Object typeNode(Type *type) const {
    json::Array elements;
    for (Type *child : type->subtypes()) elements.push_back(typeID(child));
    json::Object out{{"id", typeID(type)}, {"kind", typeKind(type)},
                     {"ir", renderType(type)}, {"elements", std::move(elements)}};
    if (auto *integer = dyn_cast<IntegerType>(type)) out["bits"] = integer->getBitWidth();
    if (auto *pointer = dyn_cast<PointerType>(type)) out["address-space"] = pointer->getAddressSpace();
    if (auto *array = dyn_cast<ArrayType>(type)) out["count"] = array->getNumElements();
    if (auto *vector = dyn_cast<VectorType>(type)) {
      out["count"] = vector->getElementCount().getKnownMinValue();
      out["scalable"] = vector->getElementCount().isScalable();
    }
    if (auto *structure = dyn_cast<StructType>(type)) {
      out["packed"] = structure->isPacked();
      out["opaque"] = structure->isOpaque();
      if (structure->hasName()) nameField(out, structure->getName());
    }
    if (auto *function = dyn_cast<FunctionType>(type)) out["varargs"] = function->isVarArg();
    return out;
  }
  json::Object moduleHeader(Module &module) const {
    json::Array functions, globals, aliases, ifuncs;
    for (Function &function : module) functions.push_back(id(&function));
    for (GlobalVariable &global : module.globals()) globals.push_back(id(&global));
    for (GlobalAlias &alias : module.aliases()) aliases.push_back(id(&alias));
    for (GlobalIFunc &ifunc : module.ifuncs()) ifuncs.push_back(id(&ifunc));
    json::Object header{{"functions", std::move(functions)}, {"globals", std::move(globals)},
                        {"aliases", std::move(aliases)}, {"ifuncs", std::move(ifuncs)}};
    stringField(header, "source-filename", module.getSourceFileName());
    stringField(header, "target-triple", module.getTargetTriple().str());
    stringField(header, "data-layout", module.getDataLayoutStr());
    return header;
  }
  json::Object records(Module &module) const {
    json::Array nodes, typeNodes;
    for (Value *value : values) nodes.push_back(node(value));
    for (Type *type : types) typeNodes.push_back(typeNode(type));
    return json::Object{{"format", "pattern.llvm.graph/1"}, {"llvm-version", LLVM_VERSION_STRING},
      {"coverage", coverage()}, {"module", moduleHeader(module)},
      {"nodes", std::move(nodes)}, {"types", std::move(typeNodes)}};
  }
  std::string signature(Module &module) const { return graphHash(records(module)); }
  json::Object exportModule(Module &module, StringRef canonicalAssembly) const {
    json::Object graphRecords = records(module);
    std::string signature = graphHash(graphRecords);
    auto header = moduleHeader(module);
    header["assembly"] = canonicalAssembly.str();
    // Presentation only: path-dependent ModuleID does not identify graph nodes.
    header["identifier"] = json::fixUTF8(module.getModuleIdentifier());
    return json::Object{
      {"graph-sha256", signature}, {"format", "pattern.llvm/1"},
      {"llvm-version", LLVM_VERSION_STRING}, {"coverage", coverage()},
      {"module", std::move(header)},
      {"nodes", std::move(*graphRecords.get("nodes"))},
      {"types", std::move(*graphRecords.get("types"))}};
  }
};

static bool allowed(User *user) {
  if (auto *ret = dyn_cast<ReturnInst>(user)) return ret->getNumOperands() == 1;
  auto *binary = dyn_cast<BinaryOperator>(user);
  if (!binary || !binary->getType()->isIntOrIntVectorTy()) return false;
  switch (binary->getOpcode()) {
    case Instruction::Add: case Instruction::Sub: case Instruction::Mul:
    case Instruction::And: case Instruction::Or: case Instruction::Xor:
    case Instruction::Shl: case Instruction::LShr: case Instruction::AShr:
    case Instruction::UDiv: case Instruction::SDiv: case Instruction::URem: case Instruction::SRem:
      return true;
    default: return false;
  }
}
static bool valueOperand(Value *value) {
  return !isa<BasicBlock>(value) && !isa<MetadataAsValue>(value) && !value->getType()->isTokenTy();
}
static bool exactKeys(const json::Object &object, std::initializer_list<StringRef> keys) {
  if (object.size() != keys.size()) return false;
  for (StringRef key : keys) if (!object.get(key)) return false;
  return true;
}
static int rewrite(Module &module, Graph &graph, StringRef inputBytes,
                   StringRef patchPath, StringRef outputPath) {
  auto patchBytes = MemoryBuffer::getFile(patchPath);
  if (!patchBytes) return fail("Cannot read patch: " + patchBytes.getError().message());
  auto parsed = json::parse((*patchBytes)->getBuffer());
  if (!parsed) return fail("Invalid patch JSON: " + toString(parsed.takeError()));
  auto *patch = parsed->getAsObject();
  if (!patch || !exactKeys(*patch, {"format", "source-sha256", "llvm-version", "graph-sha256", "edits"}) ||
      patch->getString("format") != "pattern.llvm.edits/1") return fail("Unsupported patch object/format");
  auto sourceHash = patch->getString("source-sha256");
  if (!sourceHash || *sourceHash != sha256(inputBytes)) return fail("Patch source SHA256 mismatch");
  if (patch->getString("llvm-version") != LLVM_VERSION_STRING)
    return fail("Patch LLVM version mismatch");
  auto signature = patch->getString("graph-sha256");
  if (!signature || *signature != graph.signature(module)) return fail("Patch graph identity mismatch");
  auto *edits = patch->getArray("edits");
  if (!edits) return fail("Patch edits must be an array");
  struct Edit { User *user; unsigned slot; Value *to; };
  std::vector<Edit> accepted;
  std::map<std::pair<ID, int64_t>, Value *> staged;
  auto integer = [](const json::Object &object, StringRef key) -> std::optional<int64_t> {
    const json::Value *value = object.get(key);
    auto number = value ? value->getAsUINT64() : std::nullopt;
    if (!number || *number > static_cast<uint64_t>(INT64_MAX)) return std::nullopt;
    return static_cast<int64_t>(*number);
  };
  for (const json::Value &entry : *edits) {
    auto *edit = entry.getAsObject();
    if (!edit || !exactKeys(*edit, {"user", "slot", "from", "to"})) return fail("Malformed edit");
    auto userID = integer(*edit, "user"), slot = integer(*edit, "slot");
    auto fromID = integer(*edit, "from"), toID = integer(*edit, "to");
    auto validID = [&](std::optional<int64_t> id) {
      return id && *id > 0 && static_cast<uint64_t>(*id) <= graph.values.size();
    };
    if (!validID(userID) || !validID(fromID) || !validID(toID) || !slot || *slot < 0)
      return fail("Edit IDs/slot out of range");
    auto *user = dyn_cast<User>(graph.values[*userID - 1]);
    Value *from = graph.values[*fromID - 1], *to = graph.values[*toID - 1];
    if (!user || !allowed(user) || static_cast<uint64_t>(*slot) >= user->getNumOperands())
      return fail("Edit user/opcode/operand slot not supported");
    auto position = std::make_pair(*userID, *slot);
    auto prior = staged.find(position);
    Value *current = prior == staged.end() ? user->getOperand(*slot) : prior->second;
    if (current != from) return fail("Edit from identity mismatch");
    if (!valueOperand(from) || !valueOperand(to) || from->getType() != to->getType())
      return fail("Edit operand type/kind mismatch");
    staged[position] = to;
    accepted.push_back({user, static_cast<unsigned>(*slot), to});
  }
  for (const Edit &edit : accepted) edit.user->setOperand(edit.slot, edit.to);
  std::string diagnostics;
  raw_string_ostream diagnosticStream(diagnostics);
  if (verifyModule(module, &diagnosticStream)) return fail("Rewritten module invalid: " + diagnostics);
  std::string text = assembly(module);
  // Publish a fully written sibling via exclusive hard link. Process death may
  // orphan a private temporary, but never exposes a partial final file. No fsync
  // durability claim; both names are on the same filesystem by construction.
  SmallString<256> temporary;
  int fd = -1;
  std::error_code error = sys::fs::createUniqueFile(Twine(outputPath) + ".tmp-%%%%%%", fd, temporary);
  if (error) return fail("Cannot create output temporary: " + error.message());
  raw_fd_ostream output(fd, true);
  output << text;
  output.close();
  if (output.has_error()) {
    output.clear_error();
    sys::fs::remove(temporary);
    return fail("Output write failed");
  }
  error = sys::fs::create_hard_link(temporary, outputPath);
  sys::fs::remove(temporary);
  if (error) return fail("Cannot exclusively publish output: " + error.message());
  return 0;
}

int main(int argc, char **argv) {
  if (!((argc == 3 && StringRef(argv[1]) == "import") ||
        (argc == 5 && StringRef(argv[1]) == "rewrite")))
    return fail("Usage: bridge import INPUT | bridge rewrite INPUT PATCH OUTPUT");
  auto input = MemoryBuffer::getFile(argv[2]);
  if (!input) return fail("Cannot read input: " + input.getError().message());
  std::string canonical, identifier;
  {
    // Release the original context's uniqued constants/types before the fresh
    // canonical parse; destroying Module alone leaves those context caches live.
    LLVMContext context;
    SMDiagnostic diagnostic;
    auto module = parseIR((*input)->getMemBufferRef(), diagnostic, context);
    if (!module) { diagnostic.print("pattern-llvm", errs()); return 1; }
    std::string diagnostics;
    raw_string_ostream stream(diagnostics);
    if (verifyModule(*module, &stream)) return fail("Input module invalid: " + diagnostics);
    canonical = assembly(*module);
    identifier = module->getModuleIdentifier();
  }
  // IDs describe a fresh parse of the exact emitted canonical capsule, never
  // the original bitcode/text parser's potentially different allocation state.
  LLVMContext canonicalContext;
  SMDiagnostic diagnostic;
  auto canonicalModule = parseIR(MemoryBufferRef(canonical, identifier), diagnostic, canonicalContext);
  if (!canonicalModule) { diagnostic.print("pattern-llvm", errs()); return 1; }
  std::string diagnostics;
  raw_string_ostream stream(diagnostics);
  if (verifyModule(*canonicalModule, &stream)) return fail("Canonical module invalid: " + diagnostics);
  if (assembly(*canonicalModule) != canonical) return fail("Canonical assembly is not a stable parse/print boundary");
  Graph graph(*canonicalModule);
  if (StringRef(argv[1]) == "rewrite")
    return rewrite(*canonicalModule, graph, (*input)->getBuffer(), argv[3], argv[4]);
  outs() << json::Value(graph.exportModule(*canonicalModule, canonical)) << '\n';
  return 0;
}
