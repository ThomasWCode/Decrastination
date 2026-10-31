import tseslint from "typescript-eslint";

export default tseslint.config(
  { ignores: ["dist/", "node_modules/", "test-results/", "playwright-report/"] },
  ...tseslint.configs.strict,
  {
    rules: {
      eqeqeq: ["error", "always", { null: "ignore" }],
      "@typescript-eslint/no-unused-vars": ["error", { argsIgnorePattern: "^_" }],
    },
  },
);
