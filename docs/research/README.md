# Research: Orchard launcher

> Historical research notes. They mention a separate private debloat project ("detox", `undo.sh`) and a second launcher; neither is part of this repo.

Research behind [`../PLAN.md`](../PLAN.md), the plan for **Orchard**, an Apple TV-style home
launcher for the Fire TV Stick 4K Plus. Done 2026-10-19 by 5 Sonnet researchers working in parallel,
then a Fable planner, an Opus reviewer, and a Fable revision. The TV was not touched.

## Files

| File | What it covers | Read it when |
|---|---|---|
| [tvos-visual.md](tvos-visual.md) | How the Apple TV home screen looks and moves: grid, icon, focus effect, Top Shelf, wallpaper, type, colours, Control Center, tvOS 26 Liquid Glass. `[V]` = verified, `[E]` = estimate | Designing the mockup or tuning visuals |
| [apple-hig.md](apple-hig.md) | Apple's tvOS design guidelines and principles, turned into a checklist with exact numbers | Checking a design decision |
| [repo-mining.md](repo-mining.md) | What to take from and avoid in tv-vibehack and dumbtv (MIT), and how Home on Fire works (GPLv3: integrate, never copy) | Before writing launcher code |
| [firetv-platform.md](firetv-platform.md) | Stick hardware and Fire OS 8 limits, the three reset options, banners/memory, the Mac build toolchain | Reset work, performance, build setup |
| [prior-art.md](prior-art.md) | Existing launchers (FLauncher, Projectivy, ATV-style ones), the features users like and hate, the top 10 features to include | Choosing features |
| [plan-review.md](plan-review.md) | The Opus reviewer's 23 issues with Plan Rev 1 and how each was resolved | Understanding why the plan is shaped the way it is |
| [learnings.md](../learnings.md) | Everything learned the hard way on this Stick, with the rule to follow | **Read first** before any work on the TV |

## Main takeaways

1. **Orchard works only through Home on Fire.** Fire OS ignores third-party HOME apps, so Home
   on Fire redirects the Home button to Orchard. Projectivy stays installed as the fallback.
2. **Going back to Fire TV works on 4 layers:** an in-app button (switches Home on Fire off,
   then opens Amazon home), a crash-loop safe mode, Mac commands (`launcher.sh reset-to-stock` /
   `factory-stock`), and long-press Home, which Orchard never touches.
3. **Look:** 1920x1080 canvas, safe area 80/60, 6 columns of 260x156 tiles (a 5-column option
   is tested in the mockup), focus scale 1.12 over 220 ms, Inter font, dark background `#0B0B10`.
4. **Performance budget:** plain Views, no libraries, pre-blurred 240x135 wallpaper, cold start
   under 1 s, memory ≤110 MB.
5. **Open question before Phase 0 (Q0):** should "go back to Fire TV" mean Amazon home only,
   or a fully stock Stick (debloat, DNS and update blocking undone too)?
