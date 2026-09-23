import js from '@eslint/js';
import tseslint from 'typescript-eslint';

export default tseslint.config(
  // The flat config itself is JavaScript and is not in any TypeScript project; linting it
  // with type-aware rules would need a project that contains it, which is circular.
  { ignores: ['dist', 'node_modules', 'eslint.config.js'] },
  js.configs.recommended,
  ...tseslint.configs.recommendedTypeChecked,
  {
    languageOptions: {
      parserOptions: {
        project: ['./tsconfig.eslint.json'],
        tsconfigRootDir: import.meta.dirname,
      },
    },
  },
  {
    files: ['**/*.test.ts'],
    // A test may assert on a shape the compiler cannot narrow; the rule is about production code.
    rules: { '@typescript-eslint/no-unsafe-assignment': 'off' },
  },
);
