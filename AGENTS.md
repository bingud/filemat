# AI Agent Guidelines

This file contains instructions for AI agents (and human developers) working on the **Filemat** repository.
Please adhere strictly to these guidelines to maintain code quality and consistency.

## Project Structure
- **server/**: Backend (Kotlin, Spring Boot, Gradle).
- **web/**: Frontend (SvelteKit, TypeScript, Tailwind, Vite, NPM).
- **utilities/**: Build/Maintenance scripts.

## Build, Lint, and Test Commands

### Server (Kotlin/Spring Boot)
Working Directory: `./server`
- **Build**: `./gradlew build`
- **Run Tests**: `./gradlew test`
- **Run Single Test**: `./gradlew test --tests "package.ClassName"`
- **Run Single Test Method**: `./gradlew test --tests "package.ClassName.methodName"`
- **Format/Lint**: The project seems to rely on standard IntelliJ/EditorConfig formatting.
  - *Note: Ensure you respect the `.editorconfig` (4 spaces indent).*

### Web (SvelteKit/TypeScript)
Working Directory: `./web`
- **Install Dependencies**: `npm install`
- **Dev Server**: `npm run dev`
- **Build**: `npm run build`
- **Type Check**: `npm run check` (Runs `svelte-check`)
- **Watch Checks**: `npm run check:watch`

## Code Style & Conventions

### General
- **Indentation**: 4 spaces (enforced by `.editorconfig`).
- **Line Endings**: LF.

### TypeScript / Svelte (Frontend)
- **Semicolons**: **NO semicolons** at the end of statements.
- **Quotes**: Prefer **backticks** (`) for strings, even if they don't contain variables.
- **Imports**:
  - Use absolute paths or aliases where configured (e.g., `$lib`).
  - Double quotes `"` are generally used for import paths.
- **Typing**: Strict TypeScript. Use interfaces/types defined in `$lib/types` or local `types.ts` files.
- **UI Framework**: Svelte 5 (Runes). Use `stateObjects` for complex state.
- **Styling**: Tailwind CSS v4. Use utility classes.

### Kotlin (Backend)
- **Style**: Standard Kotlin conventions.
- **Classes**: Open classes where necessary for Spring/CGLIB (e.g., `@Transactional`).
- **Nullability**: Leverage Kotlin's null safety. Avoid `!!`.
- **Migration**: Flyway migrations in `db.migration`. Java-based migrations extend `BaseJavaMigration`.
- **Data**: Spring Data JDBC / SQLite.
- **Convention**: Stored indexes of files that permissions are tied to are called "entities"

## Error Handling
- **Frontend**:
  - Use `handleErr` or `handleException` from `$lib/code/util/codeUtil.svelte` for unified error reporting/notifications.
  - Check `response.failed`, `status.notFound`, `status.serverDown` properties from `safeFetch` results.
- **Backend**:
  - Use standard Spring Boot exception handling (`@ExceptionHandler` or `ResponseStatusException`).

## AI Rules (Cursor/Copilot)
*No specific `.cursorrules` or `copilot-instructions.md` were found in the repository.*
*Please default to the conventions above.*
