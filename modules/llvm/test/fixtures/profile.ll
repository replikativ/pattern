; LLVM21 representative projection fixture, not exhaustive opcode execution.
source_filename = "pattern-profile-v2"
%Pair = type { i32, i32 }
@bytes = private unnamed_addr constant [4 x i8] c"A\00\FFz", section "payloads", align 8
@words = thread_local(localexec) global [2 x i16] [i16 4660, i16 -1], align 4
@doubles = global [2 x double] [double 0x8000000000000000, double 0x7FF8000000000042]
@vectors = global <2 x i32> <i32 7, i32 9>
@alias = alias i32 (i32), ptr @worker
@dispatch_fn = ifunc i32 (i32), ptr @resolver

declare noundef i32 @callee(i32 noundef) nounwind
declare i32 @pers(...)
declare void @throws()
declare <vscale x 4 x i32> @scalable(<vscale x 4 x i32>)
declare void @target_type(target("spirv.Event"))

define ptr @resolver() { ret ptr @worker }
define i32 @worker(i32 %worker_x) { ret i32 %worker_x }

define range(i32 0, 10) i32 @attrs(i32 range(i32 0, 10) %attr_x, ptr byval(i32) align 4 %attr_p) nounwind "target-cpu"="generic" {
  ret i32 %attr_x
}

define i32 @inspect(i32 %a, i32 %b, float %f, ptr %p) {
entry:
  %al = alloca [4 x i32], align 16
  %g = getelementptr inbounds nuw [4 x i32], ptr %al, i64 0, i64 1
  store atomic volatile i32 %a, ptr %p syncscope("singlethread") release, align 4
  %loaded = load atomic volatile i32, ptr %p syncscope("singlethread") acquire, align 4
  %rmw = atomicrmw volatile add ptr %p, i32 %b syncscope("singlethread") acq_rel, align 4
  %cx = cmpxchg weak volatile ptr %p, i32 %a, i32 %b syncscope("singlethread") acq_rel acquire, align 4
  fence syncscope("\FF") seq_cst
  %sum = add nuw nsw i32 %a, %b, !annotation !0
  %disjoint = or disjoint i32 %a, %b
  %same = icmp samesign ult i32 %a, %b
  %z = zext nneg i32 %a to i64
  %t = trunc nuw nsw i64 %z to i32
  %uf = uitofp nneg i32 %a to float
  %fa = fadd fast float %f, 1.0
  %exact = lshr exact i32 %a, 1
  %pair = insertvalue %Pair poison, i32 %a, 0
  %extracted = extractvalue %Pair %pair, 0
  %shuffled = shufflevector <2 x i32> <i32 1, i32 2>, <2 x i32> poison, <2 x i32> <i32 1, i32 0>
  %call = tail call noundef i32 @callee(i32 noundef %a) ["deopt"(i32 %a)]
  ret i32 %sum
}

define void @eh() personality ptr @pers {
entry:
  invoke void @throws() to label %normal unwind label %lpad
normal:
  ret void
lpad:
  %lp = landingpad {ptr, i32} cleanup catch ptr null
  resume {ptr, i32} %lp
}

define void @funclet() personality ptr @pers {
entry:
  invoke void @throws() to label %done unwind label %dispatch
dispatch:
  %cs = catchswitch within none [label %catch] unwind to caller
catch:
  %cp = catchpad within %cs [ptr null, i32 0, ptr null]
  catchret from %cp to label %done
done:
  ret void
}

define void @cleanup() personality ptr @pers {
entry:
  invoke void @throws() to label %done unwind label %cleanup
cleanup:
  %clean = cleanuppad within none []
  cleanupret from %clean unwind to caller
done:
  ret void
}

!named = !{!0}
!0 = !{!"opaque metadata"}
