source_filename = "profile-debug.c"

define i32 @debugged(i32 %debug_value) !dbg !4 {
entry:
  #dbg_value(i32 %debug_value, !7, !DIExpression(), !8)
  ret i32 %debug_value, !dbg !8
}

!llvm.dbg.cu = !{!0}
!llvm.module.flags = !{!9, !10}
!0 = distinct !DICompileUnit(language: DW_LANG_C11, file: !1, producer: "Pattern fixture", isOptimized: false, runtimeVersion: 0, emissionKind: FullDebug)
!1 = !DIFile(filename: "profile-debug.c", directory: ".")
!2 = !DISubroutineType(types: !3)
!3 = !{!6, !6}
!4 = distinct !DISubprogram(name: "debugged", scope: !1, file: !1, line: 1, type: !2, scopeLine: 1, spFlags: DISPFlagDefinition, unit: !0, retainedNodes: !5)
!5 = !{!7}
!6 = !DIBasicType(name: "int", size: 32, encoding: DW_ATE_signed)
!7 = !DILocalVariable(name: "debug_value", arg: 1, scope: !4, file: !1, line: 1, type: !6)
!8 = !DILocation(line: 1, column: 1, scope: !4)
!9 = !{i32 2, !"Dwarf Version", i32 5}
!10 = !{i32 2, !"Debug Info Version", i32 3}
