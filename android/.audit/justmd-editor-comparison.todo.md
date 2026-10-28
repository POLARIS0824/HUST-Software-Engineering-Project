# JustMD editor stack comparison

- [x] 1. Scope the decision the prototype exists to make: which layout, which interaction, which density, or for an empirical fork which behavior, timing, or approach. No decision means no prototype. Route to Feature.
- [x] 2. Gather references when the design space is open. Search for prior art, summarize a moodboard of themes, palettes, and layouts, let the user pick directions before building. Skip when the direction is set.
- [x] 3. Build throwaway in an isolated scratch dir, separate from production source. For a visual decision, vanilla HTML/CSS/JS or the lightest stack that renders the idea, CDN deps, a dev server with hot reload. For a behavioral or timing decision, the smallest script that exercises the question. No production framework, no tests, no abstractions.
- [x] 4. When comparing alternatives, build them behind one switcher (buttons or a keypress), each variant labeled. This is the **exhaust-the-design-space** principle skill made cheap.
- [x] 5. Verify on the matching surface. For a visual decision, screenshot each variant via the driver skill and drive the interaction. For a behavioral or timing decision, observe the thing you are deciding by logging the timing, printing the output, or watching the render. The observation is the test here, not an assertion.
- [x] 6. Present alternatives, tradeoffs, and a recommendation. The output is the decision plus the throwaway artifact, not shippable code. Hand the chosen direction to **Feature** (or `architect` for the shape) for the real build.

## Throughput checkpoint

Use the chosen B paper layout with Android Dynamic Color and Material Expressive components. Compare a CodeMirror View plus Compose implementation against a pure Compose source editor and native reading view. Reuse fixed fixtures, perform source-fidelity and real Android input/navigation checks, capture both implementations and dynamic color evidence, then deliver a selection report. Preserve round-one files in prototypes/justmd-research. New work belongs in prototypes/justmd-editor-comparison. No production persistence or full IR implementation.

## Coverage

- [x] how. Native project implementations and Compose editing/preview choices.
- [x] how. CodeMirror integration and existing prototype boundaries.
- [x] architect/arena. Two concise independent shapes, gpt-6.1-sol and gpt-6-sol, then one cross-judge.
- [x] Implement the selected disposable shape.
- [x] Verify behavior, screenshots, Dynamic Color and packaging boundaries.
- [x] Independent evidence review and report.

Step 2 preference checkpoint is skipped because B, Dynamic Color and the two technical candidates are explicitly chosen by the user.

Step 4 uses the architect-selected two APKs and comparison.html screenshot switcher to preserve native packaging isolation. Native UI selection collapse and unverified acceptance areas are explicit in issues.md and research-report.md. Independent gpt-6-sol evidence review completed. Its checklist snapshot was stale; boxes now reflect completed prototype work. Production acceptance items remain open in issues.md.
