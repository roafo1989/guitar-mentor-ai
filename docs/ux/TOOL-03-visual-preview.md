# TOOL-03 / #108 — Tuner visual direction and UX sign-off

## Approved direction (customer, 22 October 2026)

The owner **approved the revised static mockup** of the tuner in the project
conversation after rejecting the earlier rudimentary headstock and clipping.
The agreed version is a **compact dark modal**, *not a full-screen standalone
phone app*. The reference drawing is `tuner_mockup_mobile_v1.png` in the
customer conversation; it depicts the microphone-off state. It is a visual
target, not a screenshot of a running application.

- Straight-on acoustic guitar headstock, **not tilted**, symmetric 3+3;
  recognizable outline, wood finish, six metal machine heads and six strings.
- Six equally usable, fully visible circles: physical guitar strings
  **6/E2, 5/A2, 4/D3** on the left bottom-to-top and
  **3/G3, 2/B3, 1/E4** on the right top-to-bottom. Other tunings change
  octave labels without changing physical position.
- Compact header with modal close, tuning selector and Auto button; detected
  note with cents/scale above the headstock.
- Separated microphone status bar and primary Start/Stop control below.
- Original dark guitar-mentor palette aligned to the #111 metronome direction:
  charcoal/graphite `#0e151e`, mint `#96e7ca` for active/confirmed
  states, amber `#eab46b` for call-to-action. No brand logo or third-party
  headstock asset is included in application source.

## Implementation in PR #110

- `frontend/src/shared/tuner/TunerDialog.vue`: replaces the old placeholder
  headstock with an original detailed frontal SVG (wood gradients and grain,
  chrome tuners, six physically correct strings, truss-rod cover and nut).
  The six circular selectors remain semantic, keyboard/touch accessible
  buttons with both note and octave visible.
- `frontend/src/shared/tuner/tuner.css`: modal capped at 506px, standalone
  pitch panel, scaled 440:340 headstock stage and all peg buttons **inside**
  the scene bounds. For compact 360×640 and narrow 320px viewports, size and
  spacing adapt. Short screens and zoom allow vertical scrolling of modal
  contents rather than clipping or making a control unreachable.
- `frontend/src/i18n/ru.json` and `en.json`: explicit live microphone label.
  Tuner signal processing, four-frame stable-green confirmation, input
  permissions, playback arbitration and recording/metric functions are not
  modified by this visual iteration.
- Headless Vue component tests verify all six control positions, octave
  labels, manual selection and return to Auto; CI checks the whole frontend
  and metronome as before.

**Important:** The graphic uses original inline SVG and CSS; its appearance
is inspired by the approved static mock, but it is not a pixel-perfect photo
composite. Final visual match and real-browser responsive/mobile behavior
still require the owner's acceptance after independent code review.

## Historical explorations (superseded)

The earlier [six-state preview](TOOL-03-tuner-state-preview.svg) and
[360px proposal](TOOL-03-tuner-mobile-preview.svg) remain available only for
history. They are **not the approved #108 visual baseline** and must not be
treated as proof of current browser layout.

## Visual/manual acceptance still required

At desktop and **360×640 CSS px** and, where possible, at **320px width**,
check physical 6-to-1 order, all peg circles/strings visible without
horizontal clipping, short-height scroll accessibility, labels, scaling,
Tab/Shift+Tab, Escape, microphone-on/off, Standard/Drop D/Open G, live tuner
green/amber/neutral/hold/error states. Approve only after reviewing the
**running UI** against the customer-approved mock. No unperformed real-device
checks are marked PASS.

## Follow-up after UI review #5607524502 — 22 Oct 2026

The visual direction remains owner-approved, but the first SVG implementation
had oversized metal keys and outer selectors that nearly touched the scene
edge on a 320px viewport. Reviewer P2 comments were addressed without
changing DSP or Auto/Manual mapping:

- Circular string-selector centres moved inwards from x=38/402 to x=70/370
  (SVG scene width=440), with a 12px minimum interior margin **after**
  accounting for the physical control radius and an extra 5px for keyboard
  focus outline/offset.
- Six metal keys and shafts proportionally reduced and moved closer to the
  headstock, bringing the hardware-to-wood visual width ratio from 1.86
  to 1.39 while preserving exact 3+3 symmetry, string endpoints and posts.
- Mounted Vue integration test calculates actual button bounds for representative
  scene widths 282/300/322/440 CSS px and asserts complete fit/focus margin,
  3+3 chrome machine parts and 6/5/4 · 3/2/1 string mapping.

This automated geometry check is **not** equivalent to a screenshot or
user approval of the running app; actual responsive and visual acceptance
remain on the manual QA checklist.


## Further strict-review remediation — vertical peg spacing, 22 Oct 2026

The previous 71-unit vertical row pitch let adjacent 45px string buttons
nearly touch on a 320px viewport. Both physical columns now use Y=64,
168, 272 (104-unit pitch), and **SVG wood, chrome post centres,
individual strings and buttons stay aligned**. At the narrowest 282px scene,
adjacent **keyboard focus rings have >10px separation**, not just
non-overlapping touch targets. Mounted Vue regression tests verify the
vertical gaps, corner insets and alignment at four responsive sizes.

A real-device 320/360px screenshot remains an explicit QA gate and is not
replaced by these calculations.

## Owner-approved visual asset implemented — 22 Oct 2026

The new approved headstock is now an actual bundled asset:
`frontend/src/shared/tuner/approved-headstock.webp`. It was carefully cut
out and optimised directly from the *last approved realistic illustration*
in the project conversation (transparent background; ~20 KB (retina-friendly)). Unlike the
previous hand-drawn SVG, it retains the irregular wood grain, metal
reflections, natural silhouette, truss-rod plate, realistic nut and
strings which the owner explicitly selected.

The app embeds the picture in an SVG `viewBox=440×520`, with a precise
separate six-path live glow overlay and six semantic button controls.
String controls: left bottom-to-top 6/E2,5/A2,4/D3; right top-to-bottom
3/G3,2/B3,1/E4. Changing tuning updates the button targets, not the
physical positions. The image itself is decorative; keyboard/touch and
screen-reader controls stay real. On small screens the entire unit scales
together and the dialog retains vertical scrolling rather than clipping.

**Important:** A proposed artwork image alone cannot count as acceptance:
customer should compare the *running Vue tuner* on desktop/mobile to the
last approved illustration. DSP and other tools were not modified.
