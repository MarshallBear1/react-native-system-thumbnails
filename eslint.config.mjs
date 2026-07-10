import { fixupConfigRules } from '@eslint/compat';
import { FlatCompat } from '@eslint/eslintrc';
import js from '@eslint/js';
import prettier from 'eslint-plugin-prettier';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const __filename = fileURLToPath(import.meta.url);
const __dirname = path.dirname(__filename);
const compat = new FlatCompat({
  baseDirectory: __dirname,
  recommendedConfig: js.configs.recommended,
  allConfig: js.configs.all,
});
const reactNativeConfigs = fixupConfigRules(
  compat.extends('@react-native', 'prettier')
);

export default [
  ...reactNativeConfigs,
  {
    plugins: { prettier },
    rules: {
      'react/react-in-jsx-scope': 'off',
      'prettier/prettier': 'error',
    },
  },
  {
    ignores: [
      '**/.gradle/**',
      '**/build/**',
      '.turbo/**',
      'coverage/**',
      'example/ios/Pods/**',
      'example/vendor/**',
      'lib/**',
      'node_modules/**',
    ],
  },
];
