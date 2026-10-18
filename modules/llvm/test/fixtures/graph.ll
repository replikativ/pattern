; Operand multiplicity, a loop-carried PHI, wide constants and cyclic metadata.
source_filename = "pattern graph fixture"
target triple = "x86_64-pc-linux-gnu"

%Node = type { ptr, i257 }
@wide = global i257 1606938044258990275541962092341162602522202993782792835301376
@node = global %Node zeroinitializer
@alias = alias i257, ptr @wide

define i32 @main() {
entry:
  %answer = add nuw nsw i32 19, 23
  %identity = add i32 %answer, 0
  %twice = add i32 %identity, %identity
  ret i32 %twice
}

define i32 @loop(i1 %done) {
entry:
  br label %header
header:
  %i = phi i32 [ 0, %entry ], [ %next, %body ]
  br i1 %done, label %exit, label %body
body:
  %next = add i32 %i, 1
  br label %header
exit:
  ret i32 %i
}

define i32 @parallel() {
entry:
  br i1 true, label %merge, label %merge
merge:
  %value = phi i32 [ 7, %entry ], [ 7, %entry ]
  ret i32 %value
}

define i32 @frozen() {
  %value = freeze i32 poison
  ret i32 %value
}

define i32 @atomic(ptr %p) {
  %old = atomicrmw add ptr %p, i32 1 seq_cst
  fence acquire
  %value = load atomic i32, ptr %p acquire, align 4
  ret i32 %value
}

define i32 @"quoted function"(i32 %0) {
  %2 = add i32 %0, 0
  ret i32 %2
}

!pattern = !{!0}
!0 = distinct !{!0, !"cyclic metadata retained in capsule"}
