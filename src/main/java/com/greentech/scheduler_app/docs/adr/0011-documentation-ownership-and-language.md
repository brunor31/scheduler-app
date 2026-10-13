# ADR-0011 — Documentation ownership and language

## Status

Accepted on 2026-10-13, corrected after the project owner clarified that ADRs must remain in the repository as well as in Notion. The earlier interpretation that only README.md should remain was incorrect.

## Context

Detailed local documents and discovery diverged as implementation evolved. The project needs an English onboarding README and versioned architectural decisions alongside code, with detailed explanations in Brazilian Portuguese in Notion.

## Decision

Keep the root README.md and all ADR Markdown files under this directory in Git, in English. Maintain corresponding ADR pages and detailed architecture, implementation, API, operations, evidence and development guidance in the project's Notion space in pt-BR. Other detailed Markdown files remain removed after verified preservation.

Keep stable ADR numbers and explicit supersession. A change to an architectural decision must update both its versioned record and Notion counterpart in the same work item. Git retains the code-review history; Notion supplies the accessible explanation and navigation. Original documentation snapshots are historical evidence, not the current policy.

The README must support a fresh checkout: context, objective, architecture, prerequisites, setup, configuration, API examples, build/tests, key classes, operational limits and Notion links. Basic execution and CI must not depend on ignored local helpers.

The owner separately requested removal of the entire scripts directory, including testing, experiments, backup, restore and retention tools. Java tests, Maven Wrapper, Flyway migrations, deployment manifests and CI remain. Historical descriptions of removed operational scripts do not imply their availability; replacement operational tooling needs validation before production.

## Consequences

ADRs remain available offline and reviewable with code. Maintaining English records and pt-BR counterparts requires checking consistency. Detailed implementation guides are centralized in Notion instead of duplicated locally. Removing operational scripts reduces the shipped toolset and leaves an explicit production acceptance dependency.

## Verification

Verify that the root README and 11 ADRs exist, are eligible for Git tracking, and have valid links. Confirm that other migrated Markdown and the scripts directory are absent, code and Java tests remain intact, and Notion reflects the corrected policy. Preserve original and reconciled documentation snapshots; do not rewrite their historical contents. No commit or deployment is implied by these changes.
