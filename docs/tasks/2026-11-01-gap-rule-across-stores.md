# Gap rule across stores

## Goal

Stop people getting around the gap rule by booking another store of the same chain. The existing gap rule gets an optional list of stores (for example all Tiny Fish stores). With a list, a sign-up in any of them is checked against the person's pickups in all of them, using the rule's one minimum gap.

## Decisions

- **Two gaps (Eric, changed later).** A store's own gap rule stays separate, per store. The group has its own gap for pickups in different stores of the list, and being in a list is its only switch. Where this plan says a group's gap replaces a store's own value, or that switching the rule off leaves the group, that no longer applies.
- **Live preview in the panel (Eric).** The gap panel shows which days around an example pickup are blocked, and updates with the gap and the list.
- **Same timing as today (Eric).** Pickups in the next two days and past pickups don't count, the same as the current rule (`rulesAreSkipped`).
- **Cached pickup data, limit of 10 stores per group (Eric).**
- **The list is shared.** A group holds its stores and the gap, and each store is in at most one group. Saving in any member's panel updates all of them, and the gap replaces the members' previous values. Per-store lists could disagree (A lists B, B doesn't list A), which would leave the loophole open.
- **Switching the rule off means leaving the group.** Unticking "Regel aktiv" on a member removes that store from the group on save. The others keep checking each other. A group left with fewer than 2 stores is deleted, and a store leaving or left behind keeps the gap as its own `minimumGapDays`.
- **Members are keyed by store id**, so a store without an automation can be a member. Its pickups count, but sign-ups there aren't checked, and the panel warns about that.
- **Only stores the account manages** can be members, the same check as linked stores. The group belongs to one admin connection, and the rule runs only in automations of that connection.
- **Two pending sign-ups in different stores: the later `signUpDate` loses.** A confirmed pickup always blocks, and a missing `signUpDate` counts as blocking. Without this, the store evaluated first would always lose, whoever signed up first. Pairs within one store stay as they are today.
- **One fresh read before a removal.** All checks use the cached pickups. Only when a cross-store conflict would remove someone are the member stores re-read once, as the linked rule does, because someone may have just cancelled the other pickup.
- **A failed read of a member store defers the sign-up** to the next run. It never approves or declines on missing data.
- **Linked-store conflicts block saving.** If a store's linked store is in the same group and the link's window can't satisfy the gap, the save is rejected. A same-day link conflicts at any gap of 1 or more. Otherwise the longest side of the window, minus 1, must be at least the gap. Links are also checked on save from the linked-store side.
- **Removal and message.** Removal works like the other rules (dry-run logs `WOULD_DECLINE`, usual footer). A cross-store removal gets a new German message that names the other pickup. It is not editable, same as the current gap message. Removals within one store keep today's message.

## Scope

In: the store list on the gap rule, the check across stores, shared group handling, preview, conflict validation, message, i18n.

Out: a second gap value, groups as a separate admin page, editable message, gaps across different admin accounts, store application and advertisement automations.

## Changes

**Database:** new `db.changelog-043-gap-store-groups.yaml`, added to `db.changelog-master.yaml`.
- `foodsharing_gap_store_groups`: id, version, timestamps, `admin_connection_id` (FK), `minimum_gap_days`.
- `foodsharing_gap_store_group_members`: id, version, timestamps, `group_id` (FK), `store_id` (unique), `store_name`.

The group's `minimum_gap_days` is the value for members, because a member may have no automation to store it on.

**Entities and repositories:** `FoodsharingGapStoreGroup` and `FoodsharingGapStoreGroupMember` in `domain/entity`, with repositories in `repository`, modelled on `FoodsharingStoreAutomationLinkedStore(Member)`.

**Service:** `FoodsharingPickupAutomationService`.
- `evaluate` (line 1568): for a store in a group, the rule is on and uses the group's gap. Own pickups are checked as today. The group's other stores are read with `pickups(livePickupsCache, ...)`, which uses the cache, and filtered with `rulesAreSkipped` and `calendarGapDays`, plus the sign-up order above. `refreshLinkedStorePickups` does the re-read before a removal. The check also runs in the linked-store look-ahead (`linkedPickupWouldBeConfirmed`) and doesn't recurse.
- `save` (line 249): new nullable `gapStoreIds` on `StoreAutomationRequest`, holding the other stores. Null means no change. `minimumGapDays` goes to the group when there is one. Validation sits next to `validateLinkedStores`, which also gets the conflict check. Maximum 10 stores.
- `view` overlays the group's gap and rule state on `gapRuleEnabled` and `minimumGapDays`, and returns the list, so every member shows the same values and the existing fields keep working.
- Disconnecting an account (line 165) deletes that connection's groups. Deleting one store's automation (line 297) keeps the group, because membership is per store id.

**API:** `server/src/main/resources/openapi.yaml`: add the list to the automation request and response, then regenerate `client/src/app/api`. `AdminController.java:811` maps the new field.

**Client:** `admin-foodsharing-automation-page.component.{html,ts,scss}`.
- Gap panel (`html:629`): add the store multi-select, reusing the linked stores' one and `managedStores`, then the warnings and the preview.
- The preview is computed in the component from the gap and uses the same day calculation as `calendarGapDays`.
- After a save, reload with `loadStoreAutomation`, so other member stores pick up the change.
- Update `slotsDecisionGapRule*` help text (`html:479`) and `rulesGapSameStore`.

**Text:** `messages_de.properties` (new `message.automation.gap-group`), `client/public/i18n/{de,en,gws}.json`.

## UI

Yes, in the gap rule panel only. Mockup: https://claude.ai/artifact/98s6XGPhSsqcgdE2TztBMR

## Tests

The repo has no test suite (`AGENTS.md`), so none are added. Manual check on the local profile with store dry-run:
- No list: the same decisions as before.
- Confirmed pickup in A, sign-up in B inside the gap: `WOULD_DECLINE`. Outside the gap: `WOULD_CONFIRM`.
- Both pending: the later sign-up is declined.
- Member without an automation: its pickup blocks.
- Same-day link in the group: save rejected.
- Rule switched off on a member: it leaves, the others keep checking.

Also run `mvn compile`, `ng build` and `npm run i18n:check`. Migration 043 needs to run on the local profile with Hibernate validate passing.

## Done when

- [ ] A group can be created, changed and left from any member store's gap panel
- [ ] Every member store shows the same list and gap after a save
- [ ] Sign-ups inside the gap across stores are removed with the new message
- [ ] A store with an empty list behaves as today
- [ ] The preview matches what the rule decides
- [ ] Linked-store conflicts can't be saved from either panel
- [ ] Builds and `i18n:check` pass

## Risks

- **Existing bookings at launch.** People with pickups already too close together stay confirmed, and only pending sign-ups are checked. A pending one can be removed on the first run, so start with store dry-run and read the audit first.
- **Joining overwrites a store's gap.** The panel says so before saving.
- **Remaining loophole.** Because pickups in the next two days don't count (same as today), someone with a pickup tomorrow can still book another listed store a few days out.
- **Foodsharing API load.** Stores with an automation are read in every run anyway, so only members without one add reads.
- **Access changes.** If the account later loses access to a member store, sign-ups in that group wait instead of being decided, so the failure has to be visible in the logs.
- **Not tested against the real Foodsharing API** until it runs in dry-run on production.
