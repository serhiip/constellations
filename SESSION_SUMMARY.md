# Session Summary: Documentation Website (Current State)

This document captures the current state of the Constellations docs website, the build/deploy flow, issues found, fixes applied, and known caveats.

## Project Context
Constellations is a type-safe AI function-calling library for Scala 3 with these main modules:
- `constellations-core`
- `constellations-openrouter`
- `constellations-google-genai`
- `constellations-mcp`

## Docs Architecture

### Source of Truth
- Markdown docs source: `docs/src/main/mdoc/*.md` (15 pages)
- Generated Docusaurus docs output: `website/docs` (generated, ignored)
- Generated API docs output for website: `website/static/api` (generated, ignored)

### Tooling
- `sbt-mdoc` compiles/renders docs from `docs/src/main/mdoc` to `website/docs`
- `sbt-unidoc` builds Scala API docs
- Docusaurus website is in `website/` (`@docusaurus/core` and `@docusaurus/preset-classic` v3.9.2)

### Base URL
- Docusaurus base URL is `'/constellations/'`
- Local site runs at `http://localhost:3001/constellations/` in current setup

## Key File Responsibilities
- `build.sbt`
  - Defines `docs` subproject
  - Configures `mdocIn`, `mdocOut`
  - Defines `syncApiDocs` task:
    - runs `docs/Compile/unidoc`
    - copies generated unidoc from `docs/target/scala-<scalaVersion>/unidoc` to `website/static/api`
  - wires Docusaurus tasks to depend on `mdoc` + `syncApiDocs`
- `website/package.json`
  - `generate` script: `sbt "docs/mdoc" "docs/syncApiDocs"`
  - `prestart` and `prebuild` run `generate` first
- `website/docusaurus.config.js`
  - docs `editUrl` points to `docs/src/main/mdoc/<docPath>`
  - broken links set to throw
  - navbar API link points to `/constellations/api/index.html`
- `website/sidebars.js`
  - sidebar API link points to `/constellations/api/index.html`
- `website/src/theme/prism-include-languages.js`
  - custom prism language loader for Scala syntax highlighting
- `.github/workflows/docs.yml`
  - split validate/publish behavior
  - validates docs build on PRs
  - publishes on push to main/master (with key)

## Local Development Workflow

From `website/`:
- `npm install` (required if dependencies are missing)
- `npm start -- --port 3001`
  - runs `prestart` -> `npm run generate` -> starts Docusaurus

Useful direct commands:
- `npm run generate` (regenerates both docs pages and API docs)
- `sbt "docs/mdoc" "docs/syncApiDocs"` (same generation chain from project root)

## Issues Found During This Session and Fixes

### 1) Prism/Scala highlighting crashes
Symptoms:
- `Prism is not defined`
- `Cannot set properties of undefined (setting 'triple-quoted-string')`

Fixes:
- guard SSR/DOM usage properly
- bind `globalThis.Prism` while loading prism languages
- load language dependencies in order:
  - `prism-clike`
  - `prism-java`
  - `prism-scala`

### 2) API docs initially 404
Cause:
- API output not reliably available under `website/static/api` at runtime.

Fix:
- introduced explicit `syncApiDocs` task in `build.sbt`
- generation script now uses `docs/syncApiDocs`

### 3) API page opened but unstyled/broken
Cause:
- opening `/constellations/api` (no trailing slash) can break relative asset resolution for Scaladoc static HTML.

Fix:
- changed API links to `/constellations/api/index.html` in navbar and sidebar.

### 4) Dev server instability due to missing dependencies
Symptoms:
- missing webpack modules under `website/node_modules`

Fix:
- reinstalled website dependencies with `npm install`

## CI/CD Notes
- Workflow exists in `.github/workflows/docs.yml`
- It is no longer accurate to say CI/CD deploy is "not set up"
- Publishing still requires `GIT_DEPLOY_KEY` secret configured in GitHub Actions

## Known Warnings / Caveats
- `onBrokenMarkdownLinks` in Docusaurus config is deprecated and should be migrated to `markdown.hooks.onBrokenMarkdownLinks` before Docusaurus v4.
- mdoc currently emits warnings in some docs snippets (unused values/imports), but generation succeeds.
- unidoc generation can emit unresolved link warnings; output is still produced.

## Verification Status
Observed working:
- docs generation (`mdoc`) runs successfully
- API docs are generated and copied into `website/static/api`
- Docusaurus dev server starts on port 3001
- API HTML content is reachable and rendered when opened via `/constellations/api/index.html`

Recommended quick check sequence:
1. `cd website && npm start -- --port 3001`
2. Open `http://localhost:3001/constellations/`
3. Open `http://localhost:3001/constellations/api/index.html`
4. Confirm API page is styled and interactive

## Suggested Next Improvements
- Migrate deprecated Docusaurus markdown link hook config.
- Add redirect/guard so `/constellations/api` always resolves to `/constellations/api/index.html`.
- Add docs troubleshooting section to project `README.md` for local setup and common pitfalls.
