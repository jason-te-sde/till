import js from '@eslint/js'
import globals from 'globals'
import tseslint from 'typescript-eslint'
import reactHooks from 'eslint-plugin-react-hooks'
import reactRefresh from 'eslint-plugin-react-refresh'

// Type-aware rather than syntactic. The rules worth having here — no floating promises, no unsafe
// any, no unnecessary condition — all need the type checker, and most of the ones that do not are
// formatting opinions.
const typescript = [js.configs.recommended, ...tseslint.configs.strictTypeChecked]

const parser = {
  ecmaVersion: 2023,
  parserOptions: { projectService: true, tsconfigRootDir: import.meta.dirname },
}

export default tseslint.config(
  { ignores: ['dist', 'coverage', 'playwright-report', 'test-results', 'src/api/schema.d.ts'] },

  // The application and its unit tests: React, in a browser.
  {
    files: ['src/**/*.{ts,tsx}', 'test/**/*.{ts,tsx}'],
    extends: [
      ...typescript,
      // `configs.flat['recommended-latest']`. The plugin still ships its top-level
      // `configs['recommended-latest']` in the eslintrc shape, which ESLint 10 rejects with a
      // migration notice; the flat versions live one level down.
      reactHooks.configs.flat['recommended-latest'],
      reactRefresh.configs.vite,
    ],
    languageOptions: { ...parser, globals: globals.browser },
    rules: {
      // A promise nobody waits for is how a click handler silently does nothing.
      '@typescript-eslint/no-floating-promises': 'error',
      '@typescript-eslint/no-misused-promises': 'error',
      // Underscore-prefixed arguments are deliberately unused; everything else is a mistake.
      '@typescript-eslint/no-unused-vars': ['error', { argsIgnorePattern: '^_' }],
    },
  },

  // The end-to-end suite runs in Node and drives a browser. It is not React, and the React rules
  // misread Playwright's own `use` fixture as the React hook of the same name.
  {
    files: ['e2e/**/*.ts', '*.config.ts'],
    extends: typescript,
    languageOptions: { ...parser, globals: globals.node },
    rules: {
      '@typescript-eslint/no-unused-vars': ['error', { argsIgnorePattern: '^_' }],
      // Playwright requires a fixture's first argument to be a destructuring pattern even when it
      // uses no other fixture, so `({}, use)` is the framework's shape rather than a mistake. Off
      // here only, and only for that.
      'no-empty-pattern': 'off',
    },
  },

  {
    files: ['test/**/*.{ts,tsx}', 'e2e/**/*.ts'],
    rules: {
      // A test asserting on a value it knows the shape of does not need a type guard first.
      '@typescript-eslint/no-non-null-assertion': 'off',
      '@typescript-eslint/unbound-method': 'off',
    },
  },
)
