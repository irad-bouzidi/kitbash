import hljs from 'highlight.js/lib/core';
import bash from 'highlight.js/lib/languages/bash';
import dockerfile from 'highlight.js/lib/languages/dockerfile';
import gradle from 'highlight.js/lib/languages/gradle';
import ini from 'highlight.js/lib/languages/ini';
import java from 'highlight.js/lib/languages/java';
import javascript from 'highlight.js/lib/languages/javascript';
import json from 'highlight.js/lib/languages/json';
import kotlin from 'highlight.js/lib/languages/kotlin';
import markdown from 'highlight.js/lib/languages/markdown';
import plaintext from 'highlight.js/lib/languages/plaintext';
import properties from 'highlight.js/lib/languages/properties';
import sql from 'highlight.js/lib/languages/sql';
import typescript from 'highlight.js/lib/languages/typescript';
import xml from 'highlight.js/lib/languages/xml';
import yaml from 'highlight.js/lib/languages/yaml';

/*
 * Highlighting, by extension, with a fallback that cannot fail.
 *
 * §26 is explicit that an unknown extension must render as plain text rather than fail, and the
 * reason is the catalog: a recipe added next year will bring file types this list has never heard
 * of, and a preview that broke on them would make the generator look broken.
 *
 * Languages are registered individually rather than importing all of highlight.js, which is most
 * of a megabyte of languages nothing here generates.
 */

const LANGUAGES: Record<string, unknown> = {
  bash,
  dockerfile,
  gradle,
  ini,
  java,
  javascript,
  json,
  kotlin,
  markdown,
  plaintext,
  properties,
  sql,
  typescript,
  xml,
  yaml,
};

for (const [name, language] of Object.entries(LANGUAGES)) {
  hljs.registerLanguage(name, language as Parameters<typeof hljs.registerLanguage>[1]);
}

/** Extension to the language that renders it, for the extensions this catalog actually produces. */
const BY_EXTENSION: Record<string, string> = {
  bat: 'bash',
  css: 'xml',
  cts: 'typescript',
  env: 'properties',
  example: 'properties',
  gitignore: 'bash',
  gradle: 'gradle',
  html: 'xml',
  java: 'java',
  js: 'javascript',
  json: 'json',
  jsx: 'javascript',
  kt: 'kotlin',
  kts: 'kotlin',
  md: 'markdown',
  mjs: 'javascript',
  properties: 'properties',
  sh: 'bash',
  sql: 'sql',
  toml: 'ini',
  ts: 'typescript',
  tsx: 'typescript',
  xml: 'xml',
  yaml: 'yaml',
  yml: 'yaml',
};

/** Files whose whole name decides the language, because they have no extension to go on. */
const BY_NAME: Record<string, string> = {
  '.editorconfig': 'ini',
  '.gitattributes': 'bash',
  '.gitignore': 'bash',
  Dockerfile: 'dockerfile',
  gradlew: 'bash',
};

export function languageFor(path: string): string {
  const name = path.split('/').pop() ?? path;
  if (BY_NAME[name]) return BY_NAME[name];

  const extension = name.includes('.') ? (name.split('.').pop() ?? '') : '';
  return BY_EXTENSION[extension.toLowerCase()] ?? 'plaintext';
}

/**
 * The file, as HTML.
 *
 * Falls back to escaped plain text if highlighting throws — an unknown or malformed file is still
 * a file somebody asked to look at, and showing it unhighlighted beats showing an error.
 */
export function highlight(content: string, path: string): string {
  const language = languageFor(path);
  try {
    return hljs.highlight(content, { language, ignoreIllegals: true }).value;
  } catch {
    return escapeHtml(content);
  }
}

function escapeHtml(text: string): string {
  return text
    .replaceAll('&', '&amp;')
    .replaceAll('<', '&lt;')
    .replaceAll('>', '&gt;')
    .replaceAll('"', '&quot;');
}
