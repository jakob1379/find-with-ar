# AR Device Finder — v1 product and publishing specification

- Status: approved scope, implementation pending
- Target release: Google Play `1.0.0`
- Last updated: 24 July 2026

This document defines the public v1 product and its release gates. Requirements are
release-blocking unless explicitly marked as post-v1. Estimator design and current
implementation evidence remain in
[`docs/IMPLEMENTATION_PLAN.md`](docs/IMPLEMENTATION_PLAN.md); trace details and physical
experiments remain in the other documents under `docs/`.

## 1. Product definition

AR Device Finder helps a person narrow down where a stationary, nearby device is within
one room. Pro users can instead run **Complete Scan** to map likely areas for all supported
devices observed during one foreground room scan. Both modes combine observable radio
signal strength with ARCore phone movement and show honest likely areas rather than
promising exact directions before the evidence supports them.

### Audience and distribution

- Everyday users aged 18 and older.
- Worldwide Google Play release with an English-only interface and store listing.
- Native Android on ARCore-supported phones; ARCore remains required for installation.
- Category: Tools.

### Supported target

A target is supported only while Android can currently observe it:

- a Bluetooth Low Energy device must be advertising;
- a Bluetooth Classic device must remain discoverable, normally in pairing mode;
- the target must remain stationary and within the same room during a search.

Being paired, bonded, or previously connected does not make an otherwise silent device
observable. The picker must never show an unobserved paired device as findable.
In Complete Scan, “all devices” means all supported radio identities actually observed
during that foreground session. One physical device may appear more than once when Android
exposes its Low Energy and Classic radios as unrelated identities; v1 does not guess that
they are the same device.

### v1 success criteria

Public release requires all of the following:

- the physical accuracy and uncertainty gates in
  [`docs/validation-protocol.md`](docs/validation-protocol.md) pass;
- at least 80% of closed-test searches for supported targets end in **Found it** without
  assistance and within three minutes;
- Complete Scan maps every sufficiently observed target in the multi-device release trials
  without mixing observations between session-local device identities;
- the complete free and Pro flows pass on the physical-device matrix in section 8;
- users can understand the target limitations before granting permissions;
- store, privacy, advertising, billing, accessibility, and signing requirements are
  complete.

## 2. v1 scope

### Included

- One-screen illustrated onboarding and safety guidance.
- Camera, nearby-device, and location permission education and recovery.
- Nearby-device discovery, search, ranking, selection, and Pro Complete Scan mapping.
- AR-assisted collecting and likely-area visualization.
- Plain-language tracking guidance and signal-loss recovery.
- A **Found it** action and an exit survey.
- Local diagnostic traces, a ten-session history, export, sharing, and deletion.
- Global Settings, help, privacy controls, support contact, and app information.
- Consent-controlled banner advertising.
- A one-time Pro purchase that unlocks Complete Scan and removes advertising.
- Debug-only diagnostics, visualization controls, test ads, and an ad toggle.

### Explicitly out of scope

- Accounts, a backend, cloud sync, remote product analytics, or automatic trace upload.
- Saved or favourited targets and app-maintained device history.
- Per-device calibration profiles.
- Background scanning, notifications, or crowd-sourced finding.
- A signal-only mode for phones without ARCore.
- Moving-target tracking, multi-room navigation, maps, or GPS positioning.
- Wi-Fi, UWB, iOS, or device-specific proprietary integrations.
- Sound or haptic guidance.
- Interstitial, rewarded, app-open, or timed full-screen advertising.
- Cross-transport deduplication of radio identities.
- Subscriptions or paid feature gates beyond Complete Scan and removing ads.
- Automatic Play Store rating prompts.

## 3. User experience

### 3.1 First run and permissions

The first launch shows one concise, illustrated introduction before any runtime permission
request. The illustration must show a person moving the phone sideways or around a target
while a broad region narrows. It may be generated, but must use the app's visual language
and remain understandable without decorative text.

The page must explain:

- only devices that are currently advertising or discoverable can be found;
- the camera measures phone movement; camera frames are not recorded;
- nearby-device scanning and precise-location permission enable the physical estimate;
- the app does not read GPS coordinates;
- measurements stay on the phone unless the user explicitly exports or shares a trace;
- the user must stay aware of obstacles and surroundings and only locate devices they own
  or have permission to find;
- Complete Scan is only for spaces the user has permission to survey and may observe
  devices belonging to other people in radio range.

The page has one primary **Continue** action. The same guide remains available from Help.
Permissions are requested only after the explanation. A denial keeps the app usable enough
to explain the missing capability and offers **Try again** or **Open app settings**; it must
not enter a request loop.

### 3.2 Device picker

The picker starts the existing time-bounded discovery flow and provides **Scan again**.
It must:

- show a prominent **Complete Scan** action with a **Pro** label above the device list;
- show currently observed devices only;
- support case-insensitive search by the live advertised name;
- show named devices first and collapse unnamed devices under **Show other devices**;
- boost devices currently bonded by Android, without persisting app-specific target
  history or stable radio identifiers;
- otherwise rank by recent robust signal strength and keep ordering visually stable;
- label a system-paired device and its observed transport in plain language;
- explain how to wake a device or place it in pairing mode when nothing suitable appears.

For a free user, selecting **Complete Scan** opens the Pro purchase sheet with the
Play-localized one-time price and a dismissible **Not now** action. It must not start
mapping before Pro is confirmed. For a confirmed or cached Pro user, the same action starts
a fresh Complete Scan session without selecting a target.

Advertised names may be displayed during the active process but must not be written into
traces. Selecting a device or starting Complete Scan begins a fresh trace and tracking
session.

Free users see an advertising slot at the bottom of the picker. It appears only after
consent permits a request and must not sit beside list actions or cause an accidental tap.

### 3.3 Complete Scan

Complete Scan is a foreground, same-room Pro workflow. It keeps one AR pose stream active
while observing every supported radio identity seen during the session. BLE and Classic
collection may use alternating scan windows where Android cannot run them concurrently.
Each observed identity receives a session-local key and an independent estimator; hardware
addresses remain in memory only for association and are discarded when the session ends.

The primary surface is a top-down, room-relative map over the live AR session. It must:

- show observed and sufficiently localized device counts;
- list and map devices by live advertised name when available, otherwise by session-local
  key, with the observed transport shown in plain language;
- show a probability region until that device independently passes the same point and
  uncertainty gates as single-device tracking;
- keep collecting for every device when one map item is focused;
- freeze, visibly mark as **Last seen**, and stop shrinking confidence for a device whose
  signal disappears;
- explain **More movement needed** or **Not enough signal** rather than inventing a
  location;
- allow **Finish scan** at any time.

All mapped targets must remain stationary. Complete Scan cannot reveal silent paired
devices, cannot guarantee that every physical device is radio-observable, and may show one
physical device twice when its transports cannot be linked safely. The mode has no ads
because it requires Pro.

### 3.4 Tracking

The tracking surface keeps the camera and estimate as the primary content. Guidance is
visual only. The release UI automatically chooses what evidence to show:

1. **Signal found** — a warmer/colder indicator and an instruction to move sideways.
2. **Collecting** — a broad heat region, path context, and a request for more spatially
   diverse observations.
3. **Likely area found** — a point and uncertainty region only when the estimator's
   geometry and confidence gates pass.
4. **Signal lost / tracking paused** — stop shrinking confidence and tell the user how to
   recover.

The public UI uses plain language such as **Broad area**, **Narrowing**, and
**Likely area found**. Raw dBm, covariance, ambiguity, packet counts, numeric model area,
and manual visualization switches are debug-build diagnostics only. A multimodal estimate
must remain a region; it must never be collapsed into a visually convenient arrow or point.

The target name, **Devices** back action, and **Found it** action remain reachable without
covering the estimate. Ads must occupy layout space rather than overlaying the camera:

- portrait tracking uses a bottom anchored adaptive banner below the status panel;
- landscape tracking uses a 320 dp side control rail containing the status panel and a
  horizontal 320×50 banner separated from controls;
- if the landscape display cannot retain at least 480 dp for camera content, use a
  bottom landscape banner instead of the side rail;
- an unavailable, disallowed, or failed ad collapses its slot and never blocks tracking.

### 3.5 Ending a session

**Found it** ends tracking and records `YES`. Leaving through **Devices** first asks:
**Did AR Device Finder help you find the device?**

Available answers are:

- **Yes**
- **No**
- **Not sure**
- **Skip**

For **No** and **Not sure**, an optional follow-up offers:

- device disappeared;
- device was hard to identify;
- guidance was unclear;
- estimated area was wrong;
- camera, AR, Bluetooth, or app error;
- other.

An optional note is limited to 500 characters and displays: **Do not include personal
information.** The answer, reason, and note stay local unless the user explicitly exports
or shares the trace. The user can skip without being trapped. Process death or a crash
leaves an `INCOMPLETE` session rather than inventing an outcome.

Complete Scan ends through **Finish scan** and asks:
**Did Complete Scan map the devices you expected?** It uses the same answer set, optional
reasons, note limit, local-only handling, and `INCOMPLETE` process-death rule. Its outcome
stores observed and localized device counts instead of claiming that one target was found.

### 3.6 Settings

Global Settings is reachable from the picker and contains:

- **Pro: Complete Scan + no ads** — current Play-localized price, entitlement state, and
  purchase action;
- **Data** — the ten most recent sessions with export/share/delete-one and **Delete all**;
- **Privacy and ads** — privacy policy and Google's privacy choices when required;
- **Help and safety** — onboarding, supported-target limits, and troubleshooting;
- **Contact support** — prefilled email with an optional selected trace attachment;
- **About** — app version and required third-party notices.

The support draft includes app version, Android version, phone model, and a short selected
session summary. A trace is attached only after an explicit user choice and a confirmation
of what it contains. If no email application is available, show the support address so it
can be copied.

Debug builds add a clearly labelled developer section with raw session diagnostics,
visualization switches, Google's test banner, and a **Show test ads** toggle. Those controls
and test identifiers must not exist in a release build.

## 4. Monetization

### 4.1 Business model

The free app includes the complete single-device finder plus its trace, export, privacy,
and support features. Pro is a permanent, non-consumable Google Play product:

| Property | Decision |
| --- | --- |
| Product ID | `pro_lifetime` |
| Public label | Pro — Complete Scan + no ads |
| Base price | €4.99 equivalent, localized by Google Play |
| Entitlement | Unlock Complete Scan and suppress all ad requests and ad slots |
| Subscription | None |
| Other paid features | None |

The UI always displays the formatted price returned by Google Play and never hard-codes a
localized amount.

### 4.2 Purchase lifecycle

Google Play Billing is the entitlement source. The client must:

- query purchases at app start and whenever the app returns to the foreground;
- grant Pro only for `PURCHASED`, non-consumed `pro_lifetime` purchases;
- acknowledge a new purchase promptly;
- show a pending state without granting Pro for `PENDING`;
- restore the entitlement automatically on another device using the same Play account;
- cache a confirmed entitlement so an existing Pro user retains Complete Scan and remains
  ad-free offline;
- revoke the cache when a later successful Play query confirms the purchase is absent;
- keep finding functional and show a retryable message when Billing is unavailable.

V1 uses no purchase-verification backend. Client-only validation is sufficient for a
single low-value lifetime entitlement; server validation is reconsidered only if measured
fraud materially affects revenue.

### 4.3 Advertising

Use Google AdMob banner ads only on the picker and tracking surfaces. AdMob controls normal
banner refresh; the app must not add its own interval or force a refresh around user
actions.

An ad request is allowed only when:

- the build is the free release, or a debug user has enabled test ads;
- Pro is not confirmed or cached;
- Google's consent SDK reports that ads may be requested.

Consent-controlled personalization is used worldwide. Personalized ads are allowed only
where the user has permitted them. Settings must expose the Google privacy-options form
when required. A consent failure uses the last valid consent state when available; otherwise
the app makes no ad request and continues without an ad.

Production ad identifiers are injected through release configuration. Debug builds use
Google's official test banner identifier. Live ads must never be requested by a debug
build.

## 5. Metrics, logging, and local traces

### 5.1 Product measurement boundary

V1 has no remote analytics SDK and no publisher telemetry endpoint. Public outcome rates
are therefore available only from traces users voluntarily send. Closed testers must
submit their outcome and trace as part of the validation protocol.

Operational monitoring uses:

- Android Vitals for crashes and ANRs;
- Play Console for purchases and refunds;
- AdMob for ad delivery and revenue;
- support emails and explicitly shared traces for product diagnosis.

Do not add Firebase Analytics, Crashlytics, an advertising-derived product identifier, or
automatic trace upload.

### 5.2 Trace schema v2

V1 writes UTF-8 NDJSON with `schema_version: 2`. It retains the current observation
semantics and adds these record types:

| Record | Required content |
| --- | --- |
| `session` | Session ID, mode (`SINGLE_TARGET` or `COMPLETE_SCAN`), creation time, app version, phone model, and coordinate frame; single-target mode also includes the selected target's redacted metadata |
| `observation` | Session-local device key, transport, and existing synchronized radio, AR pose, filtering, acceptance, and estimator fields |
| `event` | Monotonic time, optional session-local device key, and a bounded code for AR state, scanner state, signal loss/recovery, estimator-state change, or recoverable failure |
| `outcome` | End time, duration, `YES`/`NO`/`NOT_SURE`/`SKIPPED`/`INCOMPLETE`, optional reason/note, counts, path length, and either the selected target's final state or Complete Scan's observed/localized counts and per-device final states |

Release event records use stable codes and sanitized bounded details, not stack traces or
arbitrary exception text. Debug details remain in Logcat.

Analysis tools must accept both schema v1 and v2. Existing v1 fixtures remain valid, and
cross-language v2 fixtures must cover event and outcome records plus a multi-device
Complete Scan without identity crossover.

### 5.3 Retention and export

- Store one trace per session in app-private storage.
- Exclude traces from Android backup and device transfer.
- Keep the newest ten traces, excluding an active file; after a session closes, delete the
  oldest excess files.
- Allow raw NDJSON export through Android's system document picker.
- Allow explicit sharing through the Android share sheet.
- Allow deletion of one trace or all traces.
- A failed deletion remains visible and reports a recoverable error.

### 5.4 Trace privacy

Never persist:

- Bluetooth hardware addresses or other stable target identifiers;
- advertised device names;
- GPS coordinates;
- camera images or video;
- Play account, advertising identifier, email address, or contact data.

An exported trace may contain relative phone poses, timestamps, radio measurements,
session-local device keys, transport, manufacturer/service identifiers, phone model,
estimator results for one or multiple devices, the selected outcome, and the optional user
note. The export and support flows must summarize this before sharing.

## 6. Privacy, consent, and safety

The privacy policy and Play Data Safety form must describe every SDK in the shipped build,
not only data handled directly by AR Device Finder.

| Data path | Handling |
| --- | --- |
| Camera and AR poses | Camera access is live; frames are not stored. Relative poses stay in local traces. |
| Nearby-device observations | Used for single-device finding or Complete Scan mapping; redacted traces may contain multiple devices' relative locations and stay local until explicit export/share. |
| Location permission | Required for the physical estimate and platform scanning rules; GPS is not read. |
| AdMob | Google may process IP-derived approximate location, interactions, diagnostics, and device identifiers for ads, analytics, and fraud prevention. |
| Play Billing | Google handles purchase and Play account data; the app keeps only entitlement state and purchase metadata needed by Billing. |
| Support email | The user chooses the message and any trace sent to the future organization support address. |

Before the first ad request, use Google's User Messaging Platform consent flow. The in-app
privacy policy must cover collection, sharing, retention, deletion, support contact, AdMob,
and Play Billing and must match the Play Data Safety answers.

AR Device Finder is not designed for children and declares an 18+ target audience. Store
art must not be child-directed. The app has no account-creation or account-deletion flow.

The onboarding and Help page must state that estimates can be wrong, the app is not an
emergency or theft-recovery service, users must watch their surroundings, and they may only
locate devices they own or have permission to find.

## 7. Branding and store presentation

The public name is **AR Device Finder**. The initial short-description direction is:

> Use nearby signals and AR to narrow down where a lost device may be.

The title and primary artwork must not include the Bluetooth word or figure mark. Store and
help copy may identify Bluetooth technology factually only after the publisher reviews the
current Bluetooth SIG licence and attribution rules. Do not abbreviate the mark as “BT” in
public copy; the brand guide explicitly disallows that abbreviation. Internal source and
technical documentation may retain established API terms.

Required launch assets:

- an adaptive launcher icon with foreground, background, and monochrome variants;
- a 512×512 PNG Play listing icon;
- a 1024×500 feature graphic;
- at least five accurate phone screenshots covering onboarding, picker, collecting,
  likely-area, and Complete Scan states, including portrait and landscape tracking;
- concise English short and full descriptions that state the observable-device,
  stationary-target, same-room, and ARCore limitations;
- useful alt text for every uploaded graphic;
- no preview video for v1.

Do not claim exact direction, guaranteed recovery, support for all paired devices, or
calibrated accuracy until the corresponding held-out physical evidence exists.

## 8. Release acceptance

### 8.1 Physical matrix

Test on:

- three different ARCore-supported phone models spanning at least two Android versions;
- three Bluetooth Low Energy targets with controllable advertising;
- two Bluetooth Classic targets that can remain discoverable.

Each phone must exercise portrait and landscape, free and Pro, online and offline, at least
one Low Energy and one Classic target, and a Complete Scan with at least three stationary
targets. Across the matrix, at least one Complete Scan must contain both transports.
Complete the open and furnished-room, height, occlusion, and held-out trials in the
validation protocol.

### 8.2 Accuracy and usability gates

- At least 95% of accepted radio observations have a sufficiently recent valid AR pose.
- Open-room median horizontal error is at most 1.5 m.
- Furnished-room median horizontal error is at most 2.5 m.
- Held-out 90th-percentile error is at most 3 m.
- Nominal 80% regions have 70–90% empirical coverage.
- Time to first useful region is under 15 seconds for a target advertising at least once
  per second; Classic performance is reported separately.
- The estimator beats the strongest-signal baseline on held-out traces.
- Complete Scan applies the accuracy and coverage gates independently to each target that
  reaches the documented initialization threshold; every other observed target remains
  visible with an explicit insufficient-evidence state.
- Deterministic multi-device replay attributes every observation to the correct
  session-local device and produces byte-identical per-device estimator inputs.
- At least 80% of supported closed-test searches finish unassisted with **Found it** within
  three minutes.
- A first-time user can explain why movement is needed and why some paired devices cannot
  be selected.

### 8.3 Reliability and accessibility gates

- Twenty consecutive discover/select/track/finish cycles on each phone complete without a
  crash, stuck scanner, unavailable camera, or corrupt trace.
- Ten consecutive Complete Scan sessions on each phone finish without identity crossover,
  a stuck scanner, an unavailable camera, a corrupt trace, or an unresponsive map.
- A ten-minute session causes no forced stop, severe thermal state, or unusable degradation
  in camera and scan behaviour.
- Bluetooth-off, permission denial, AR installation/update, camera unavailable, tracking
  loss, signal loss, ad failure, Billing failure, export failure, and process recreation are
  recoverable and do not display stale confidence.
- The core finder works without internet access.
- Controls have TalkBack labels, at least 48 dp targets, logical focus order, sufficient
  contrast, and remain usable at 200% font scaling.
- No essential state is communicated by colour alone.
- Ads remain visually separated from controls in every supported size and orientation.
- A confirmed or cached Pro entitlement unlocks Complete Scan and produces no ad request
  or ad slot; a free, pending, cancelled, or revoked entitlement cannot start the mode.
- Test matrices cover consent required/accepted/rejected, purchase success/pending/cancel,
  restore after reinstall, offline Pro, and refund reconciliation.
- Automated tests prove that single- and multi-device exported traces contain neither
  addresses nor advertised names and that schema v1/v2 replay remains deterministic.

### 8.4 Play publishing gate

- Set `versionName` to `1.0.0`; choose the permanent application ID before creating the
  Play app. The current development ID is not yet approved as the production ID.
- Target API 37 or a later Play-accepted level and produce a signed Android App Bundle.
- Enrol in Play App Signing and protect a separate upload key outside the repository.
- Remove debug menus, test ad IDs, verbose sensitive logs, and development endpoints from
  the release artifact.
- Complete the privacy policy URL, Data Safety, ads, target-audience, content-rating, and
  app-access declarations.
- Declare that the app contains ads and offers a one-time in-app product.
- Complete developer identity and physical-device verification.
- If the eventual account is subject to the new-personal-account rule, keep at least 12
  testers opted into a closed test for 14 continuous days before applying for production.
- Resolve all closed-test release gates, then publish worldwide at 100% rather than using a
  staged percentage rollout.

## 9. Publisher-owned launch inputs

Core application work may continue with development identifiers, test ads, and a fake
debug entitlement. The following values are required before the stated boundary:

| Input | Required by |
| --- | --- |
| Organization legal name and domain | Privacy policy and public store listing |
| Permanent Android application ID | Play app creation and real Billing integration |
| Organization support email alias | Closed testing |
| Public privacy-policy webpage and URL | Closed testing |
| Play Console developer account | Internal/closed distribution and Billing tests |
| AdMob app and banner identifiers | Release-candidate ad validation |
| Activated `pro_lifetime` product and regional prices | Release-candidate Billing validation |
| Upload key and Play App Signing setup | First Play upload |

The publisher will add the privacy webpage later. Until it exists, the app may link to a
clearly marked development placeholder only in debug builds; a release build must fail its
publishing checklist rather than ship a dead or placeholder URL.

The repository is to become private before public launch. Making it private does not revoke
copies or forks created while it was public, so no secret, signing key, production ad ID, or
service credential may ever be committed.

## 10. References

- [Google Play ads policy](https://support.google.com/googleplay/android-developer/answer/9857753)
- [Google Play payments policy](https://support.google.com/googleplay/android-developer/answer/9858738)
- [Google Play user-data policy](https://support.google.com/googleplay/android-developer/answer/10144311)
- [Google Play Data Safety guidance](https://support.google.com/googleplay/android-developer/answer/10787469)
- [Google Mobile Ads data disclosure](https://developers.google.com/admob/android/privacy/play-data-disclosure)
- [Google Mobile Ads banner guidance](https://developers.google.com/admob/android/banner)
- [Google Play testing requirements](https://support.google.com/googleplay/android-developer/answer/14151465)
- [Google Play preview-asset requirements](https://support.google.com/googleplay/android-developer/answer/9866151)
- [Android app signing](https://developer.android.com/studio/publish/app-signing)
- [Bluetooth trademark brand guide](https://www.bluetooth.com/wp-content/uploads/2022/06/Brand-Guide-for-Bluetooth-Trademarks_Jun2022.pdf)
