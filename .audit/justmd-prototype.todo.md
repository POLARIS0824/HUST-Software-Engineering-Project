# JustMD prototype research

- [x] 1. Scope the decision the prototype exists to make: which layout, which interaction, which density, or for an empirical fork which behavior, timing, or approach. No decision means no prototype. Route to Feature.
- [x] 2. Gather references when the design space is open. Search for prior art, summarize a moodboard of themes, palettes, and layouts, let the user pick directions before building. Skip when the direction is set.
- [x] 3. Build throwaway in an isolated scratch dir, separate from production source. For a visual decision, vanilla HTML/CSS/JS or the lightest stack that renders the idea, CDN deps, a dev server with hot reload. For a behavioral or timing decision, the smallest script that exercises the question. No production framework, no tests, no abstractions.
- [x] 4. When comparing alternatives, build them behind one switcher (buttons or a keypress), each variant labeled. This is the **exhaust-the-design-space** principle skill made cheap.
- [x] 5. Verify on the matching surface. For a visual decision, screenshot each variant via the driver skill and drive the interaction. For a behavioral or timing decision, observe the thing you are deciding by logging the timing, printing the output, or watching the render. The observation is the test here, not an assertion.
- [x] 6. Present alternatives, tradeoffs, and a recommendation. The output is the decision plus the throwaway artifact, not shippable code. Hand the chosen direction to **Feature** (or `architect` for the shape) for the real build.

## Throughput checkpoint

The deliverable is one disposable prototype under prototypes/justmd-research, three switchable design directions using the same content, Android and editor feasibility evidence, screenshots, and a research report. Build only the interactions that resolve a decision. Keep the docs baseline unchanged. Do not claim production readiness.

## Exploration coverage

- [x] Frame. Two read-only how explorers partition editor and index/storage research.
- [x] Fan out. gpt-6.1-sol for both explorers, per user override.
- [x] Aggregate. Both returned ISSUES with official source links and explicit runtime gaps.
- [x] Report. Save findings in the final research report.

## Architect

- [x] Ground. Greenfield isolated prototype. Existing design constraints summarized in grounding.md.
- [x] Sketch. Two independent design packages, gpt-6.1-sol and gpt-6-sol.
- [x] Agree. Default proceed. No user checkpoint requested.
- [x] Implement. Fill only the throwaway probes and shared style switcher.
- [x] Scrap. skip: only needed if repeated structural deviations occur.

## Arena

- [x] Frame. Compare the smallest native prototype shapes. Rubric in grounding.md.
- [x] Fan out.
- [x] Cross-judge.
- [x] Pick.
- [x] Graft.
- [x] Verify.

## Completion notes

- Prototype step 2 upfront preference checkpoint skipped because the user already named the three directions and requested the choice after screenshots.
- Prototype step 3 uses a minimal Android host because real Compose/WebView/Room integration is part of the empirical question.
- Architect Scrap stayed unused. A local native-view identity defect was fixed within the selected boundary.
- Six same-state screenshots, empty search screenshot, raw probes, research report, tracked findings and rebuild instructions are present.
- No commit, PR, or deployment. This directory is not a Git checkout and the prototype is disposable.

