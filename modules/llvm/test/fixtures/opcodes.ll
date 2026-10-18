; LLVM21 structural-inspection corpus supplementing profile.ll/graph.ll.
; Parsing/verifying/importing this file does NOT execute its functions. Inputs
; deliberately have no runtime guards: division/conversion/shift operations may
; produce poison for some arguments. This is opcode coverage, not behavior tests.
source_filename = "pattern-llvm21-opcode-coverage"
target triple = "x86_64-unknown-linux-gnu"

define i32 @integer_ops(i32 %a, i32 %b, i8 %small) {
entry:
  %addition = add nuw nsw i32 %a, %b
  %inclusive_or = or disjoint i32 %a, %b
  %shift_logical = lshr exact i32 %a, %b
  %subtract = sub i32 %a, %b
  %multiply = mul i32 %a, %b
  %unsigned_divide = udiv i32 %a, %b
  %signed_divide = sdiv i32 %a, %b
  %unsigned_remainder = urem i32 %a, %b
  %signed_remainder = srem i32 %a, %b
  %shift_left = shl i32 %a, %b
  %shift_arithmetic = ashr i32 %a, %b
  %conjunction = and i32 %a, %b
  %exclusive_or = xor i32 %a, %b
  %sign_extended = sext i8 %small to i32
  ret i32 %exclusive_or
}

define float @float_ops(float %a, float %b, double %wide, i32 %integer) {
entry:
  %negate = fneg float %a
  %subtract = fsub float %a, %b
  %multiply = fmul float %a, %b
  %divide = fdiv float %a, %b
  %remainder = frem float %a, %b
  %compare = fcmp nnan oeq float %a, %b
  %to_unsigned = fptoui float %a to i32
  %to_signed = fptosi float %a to i32
  %from_signed = sitofp i32 %integer to float
  %narrow = fptrunc nnan double %wide to float
  %extend = fpext nnan float %a to double
  ret float %narrow
}

define ptr @pointer_and_bits(ptr %pointer, i64 %integer, i32 %bits) {
entry:
  %as_integer = ptrtoint ptr %pointer to i64
  %as_pointer = inttoptr i64 %integer to ptr
  %as_float = bitcast i32 %bits to float
  %other_space = addrspacecast ptr %pointer to ptr addrspace(1)
  %default_space = addrspacecast ptr addrspace(1) %other_space to ptr
  ret ptr %default_space
}

define <4 x i32> @vector_elements(<4 x i32> %vector, i32 %value) {
entry:
  %element = extractelement <4 x i32> %vector, i32 0
  %inserted = insertelement <4 x i32> %vector, i32 %value, i32 1
  ret <4 x i32> %inserted
}

define i32 @switch_cases(i32 %value) {
entry:
  switch i32 %value, label %other [
    i32 0, label %zero
    i32 17, label %seventeen
  ]
zero:
  ret i32 0
seventeen:
  ret i32 17
other:
  unreachable
}

define void @indirect_branch(i1 %condition) {
entry:
  %target = select i1 %condition,
    ptr blockaddress(@indirect_branch, %left),
    ptr blockaddress(@indirect_branch, %right)
  indirectbr ptr %target, [label %left, label %right]
left:
  ret void
right:
  ret void
}

; A valid asm-goto/callbr shape. The bridge only inspects it; no inline assembly
; or native function is executed by the fixture contract.
define void @asm_branch() {
entry:
  callbr void asm sideeffect "", "!i"()
    to label %fallthrough [label %target]
fallthrough:
  ret void
target:
  ret void
}

; The ptr denotes a target-specific va_list supplied by a hypothetical caller.
; No test invokes this function or claims ABI behavior from this inspection.
define i32 @vararg_read(ptr %list) {
entry:
  %value = va_arg ptr %list, i32
  ret i32 %value
}
