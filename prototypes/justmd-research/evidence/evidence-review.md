# Independent evidence review

Reviewed by gpt-6-sol. Scope was the decision trail, current chat transcript, raw probe outputs, hashes, report claims, and six style screenshots. This was a prototype evidence audit, not a production code or security review.

The reviewer found all ten evidence paths available at that point, verified the published hashes, confirmed that the style screenshots were genuine emulator captures, and supported the report's narrow Room, normal storage-path, Vditor fidelity, and CodeMirror source-model conclusions.

## Flags and root follow-up

| Reviewer flag | Follow-up evidence | State |
| --- | --- | --- |
| Vditor heading pseudo-labels clipped at the screen edge | Decorative pseudo-elements removed; all three editor screenshots opened and checked after recapture | Resolved in prototype |
| Backlink navigation failed with blank WebView | Note composition keyed by ID; same sheet selection repeated; backlink-open-after-fix.json contains SYN body and no old cwnd body | Resolved in prototype |
| Rebuild makes old APK summary hash stale | summarize.mjs rerun after final build and probes | Refreshed |
| Console log contains old and new CSP errors | research-report.md explicitly labels it cumulative and avoids using it alone as final-policy proof | Disclosed |
| Library FAB covers parts of lower rows | Report records overlap and continued scrolling; formal layout choice remains for next implementation | Remaining visual limitation |

The first review preceded the final native-view and decoration corrections. Root owns the subsequent repeat checks. All six main comparison screenshots now come from the corrected final APK. The empty-state screenshot predates those two changes; its native search layout was unaffected.

Remaining product gates are explicit in research-report.md and issues.md. None is counted as passed by this audit.
