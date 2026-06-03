import crypto from 'crypto';
import fs from 'fs';
import path from 'path';
import { redactText, truncateText } from './lib/redact.js';

const fsp = fs.promises;

export const MAX_RAW_UPLOAD_BYTES = 20 * 1024 * 1024;
export const MAX_ATTACHMENTS_PER_MESSAGE = 8;
export const MAX_SESSION_ATTACHMENTS = 80;

const MAX_IMAGE_BYTES = 10 * 1024 * 1024;
const MAX_TEXT_BYTES = 2 * 1024 * 1024;
const MAX_DOCUMENT_BYTES = 20 * 1024 * 1024;
const MAX_EXTRACTED_CHARS = 80000;
const PREVIEW_CHARS = 700;

const IMAGE_EXTENSIONS = new Set(['jpg', 'jpeg', 'png', 'webp', 'gif']);
const TEXT_EXTENSIONS = new Set([
  'txt',
  'md',
  'markdown',
  'json',
  'jsonl',
  'xml',
  'yaml',
  'yml',
  'log',
  'ini',
  'toml',
  'env',
]);
const CODE_EXTENSIONS = new Set([
  'js',
  'jsx',
  'ts',
  'tsx',
  'mjs',
  'cjs',
  'py',
  'java',
  'kt',
  'kts',
  'go',
  'rs',
  'c',
  'cc',
  'cpp',
  'h',
  'hpp',
  'cs',
  'css',
  'scss',
  'html',
  'htm',
  'sh',
  'bash',
  'zsh',
  'ps1',
  'bat',
  'cmd',
  'sql',
  'rb',
  'php',
  'swift',
  'lua',
  'r',
  'dart',
  'vue',
  'svelte',
]);
const SPREADSHEET_EXTENSIONS = new Set(['xlsx', 'csv']);
const WORD_EXTENSIONS = new Set(['docx', 'doc']);

const MIME_EXTENSIONS = new Map([
  ['image/jpeg', 'jpg'],
  ['image/png', 'png'],
  ['image/webp', 'webp'],
  ['image/gif', 'gif'],
  ['application/pdf', 'pdf'],
  ['application/vnd.openxmlformats-officedocument.wordprocessingml.document', 'docx'],
  ['application/msword', 'doc'],
  ['application/vnd.openxmlformats-officedocument.spreadsheetml.sheet', 'xlsx'],
  ['text/csv', 'csv'],
  ['application/json', 'json'],
  ['application/xml', 'xml'],
  ['text/xml', 'xml'],
  ['text/markdown', 'md'],
  ['text/plain', 'txt'],
]);

export function sanitizeAttachmentName(value) {
  const base = path.basename(String(value || 'attachment')).replace(/[\u0000-\u001f<>:"/\\|?*]+/g, '_');
  const compact = base.replace(/\s+/g, ' ').trim();
  return compact.slice(0, 140) || 'attachment';
}

export function classifyWorkbenchAttachment(input = {}) {
  const name = sanitizeAttachmentName(input.name);
  const mime = normalizeMime(input.mime);
  const ext = normalizeExtension(path.extname(name).slice(1)) || MIME_EXTENSIONS.get(mime) || '';
  const kind = inferKind(ext, mime);
  if (!kind) return { name, mime, ext, kind: '', maxBytes: 0 };
  return { name, mime, ext: ext || defaultExtension(kind), kind, maxBytes: maxBytesForKind(kind) };
}

export function publicAttachment(attachment) {
  return {
    id: attachment.id,
    name: attachment.name,
    mime: attachment.mime,
    kind: attachment.kind,
    size: attachment.size,
    status: attachment.status,
    textPreview: attachment.textPreview || '',
    createdAt: attachment.createdAt,
  };
}

export async function saveWorkbenchAttachment(sessionId, input = {}) {
  const buffer = Buffer.isBuffer(input.buffer) ? input.buffer : Buffer.from(input.buffer || '');
  const classified = classifyWorkbenchAttachment({
    name: input.name,
    mime: input.mime,
  });
  if (!classified.kind) {
    throw apiError('UNSUPPORTED_ATTACHMENT_TYPE', 'Unsupported attachment type.', 415);
  }
  if (buffer.length <= 0) {
    throw apiError('EMPTY_ATTACHMENT', 'Attachment is empty.', 400);
  }
  if (buffer.length > classified.maxBytes) {
    throw apiError(
      'ATTACHMENT_TOO_LARGE',
      `Attachment exceeds ${formatBytes(classified.maxBytes)} limit.`,
      413
    );
  }

  const id = `att_${crypto.randomUUID()}`;
  const dir = path.join(uploadRoot(), safePathSegment(sessionId));
  await fsp.mkdir(dir, { recursive: true });
  const filePath = path.join(dir, `${id}.${classified.ext || 'bin'}`);
  await fsp.writeFile(filePath, buffer, { flag: 'wx' });

  const createdAt = Date.now();
  const attachment = {
    id,
    sessionId,
    name: classified.name,
    mime: classified.mime,
    kind: classified.kind,
    ext: classified.ext,
    size: buffer.length,
    status: 'ready',
    filePath,
    extractedText: '',
    textPreview: '',
    createdAt,
  };

  if (classified.kind !== 'image') {
    const extractedText = await extractAttachmentText(attachment, buffer);
    attachment.extractedText = extractedText;
    attachment.textPreview = truncateText(extractedText, PREVIEW_CHARS);
  }

  return attachment;
}

export function composePromptWithAttachments(text, attachments = []) {
  const userText = String(text || '').trim() || '请分析这些附件。';
  if (!attachments.length) return userText;

  const blocks = [
    '以下是用户从手机工作台上传的附件。请把它们作为本轮对话上下文；如果附件内容和用户文字冲突，以用户最新文字为准。',
  ];
  attachments.forEach((attachment, index) => {
    const n = index + 1;
    if (attachment.kind === 'image') {
      blocks.push(
        `附件 ${n}: ${attachment.name} (${attachment.kind}, ${formatBytes(attachment.size)})。图片已作为视觉输入附加。`
      );
      return;
    }
    const body = attachment.extractedText || attachment.textPreview || '';
    blocks.push(
      [
        `--- ATTACHMENT ${n}: ${attachment.name} (${attachment.kind}, ${formatBytes(attachment.size)}) ---`,
        body || '[未能提取文本内容]',
        `--- END ATTACHMENT ${n} ---`,
      ].join('\n')
    );
  });

  return `${userText}\n\n${blocks.join('\n\n')}`;
}

export function formatAttachmentList(attachments = []) {
  if (!attachments.length) return '';
  return attachments.map((item) => `- ${item.name} (${item.kind}, ${formatBytes(item.size)})`).join('\n');
}

export async function removeAttachmentFiles(attachments = []) {
  const root = uploadRoot();
  await Promise.all(
    attachments
      .map((attachment) => attachment?.filePath)
      .filter(Boolean)
      .map((filePath) => removeAttachmentFile(root, filePath))
  );
}

async function removeAttachmentFile(root, filePath) {
  const resolved = path.resolve(String(filePath || ''));
  if (!isInsidePath(resolved, root)) return;
  await fsp.rm(resolved, { force: true }).catch(() => {});
  await fsp.rm(path.dirname(resolved), { force: true, recursive: false }).catch(() => {});
}

function uploadRoot() {
  return path.resolve(process.env.AM_WORKBENCH_UPLOAD_DIR || path.join(process.cwd(), '.workbench-uploads'));
}

function normalizeMime(value) {
  return String(value || 'application/octet-stream').split(';')[0].trim().toLowerCase() || 'application/octet-stream';
}

function normalizeExtension(value) {
  return String(value || '').trim().toLowerCase().replace(/^\./, '');
}

function inferKind(ext, mime) {
  if (IMAGE_EXTENSIONS.has(ext) || mime.startsWith('image/')) return 'image';
  if (ext === 'pdf' || mime === 'application/pdf') return 'pdf';
  if (WORD_EXTENSIONS.has(ext) || mime.includes('wordprocessingml') || mime === 'application/msword') return 'word';
  if (SPREADSHEET_EXTENSIONS.has(ext) || mime.includes('spreadsheetml')) {
    return 'spreadsheet';
  }
  if (CODE_EXTENSIONS.has(ext)) return 'code';
  if (TEXT_EXTENSIONS.has(ext) || mime.startsWith('text/') || mime.endsWith('+json') || mime.endsWith('+xml')) return 'text';
  return '';
}

function defaultExtension(kind) {
  if (kind === 'image') return 'bin';
  if (kind === 'pdf') return 'pdf';
  if (kind === 'word') return 'docx';
  if (kind === 'spreadsheet') return 'xlsx';
  return 'txt';
}

function maxBytesForKind(kind) {
  if (kind === 'image') return MAX_IMAGE_BYTES;
  if (kind === 'text' || kind === 'code') return MAX_TEXT_BYTES;
  return MAX_DOCUMENT_BYTES;
}

async function extractAttachmentText(attachment, buffer) {
  try {
    if (attachment.kind === 'text' || attachment.kind === 'code') {
      return normalizeExtractedText(buffer.toString('utf8'));
    }
    if (attachment.kind === 'pdf') {
      const { PDFParse } = await import('pdf-parse');
      const parser = new PDFParse({ data: buffer });
      try {
        const data = await parser.getText();
        return normalizeExtractedText(data?.text || '');
      } finally {
        await parser.destroy();
      }
    }
    if (attachment.kind === 'word') {
      return await extractWordText(attachment);
    }
    if (attachment.kind === 'spreadsheet') {
      return await extractSpreadsheetText(attachment, buffer);
    }
    return '';
  } catch (err) {
    await fsp.rm(attachment.filePath, { force: true }).catch(() => {});
    throw apiError(
      'ATTACHMENT_PARSE_FAILED',
      `Failed to parse ${attachment.kind} attachment: ${err?.message || err}`,
      422
    );
  }
}

async function extractWordText(attachment) {
  if (attachment.ext === 'docx') {
    const mod = await import('mammoth');
    const mammoth = mod.default || mod;
    const result = await mammoth.extractRawText({ path: attachment.filePath });
    return normalizeExtractedText(result?.value || '');
  }

  const mod = await import('word-extractor');
  const WordExtractor = mod.default || mod.WordExtractor || mod;
  const extractor = new WordExtractor();
  const doc = await extractor.extract(attachment.filePath);
  return normalizeExtractedText(doc.getBody());
}

async function extractSpreadsheetText(attachment, buffer) {
  if (attachment.ext === 'csv') return normalizeExtractedText(buffer.toString('utf8'));

  const mod = await import('read-excel-file/node');
  const readExcelFile = mod.default;
  const sheets = await readExcelFile(buffer);
  const parts = [];
  for (const sheet of sheets || []) {
    const rows = (sheet.data || [])
      .map((row) => row.map(formatSpreadsheetCell).join('\t'))
      .filter((row) => row.trim());
    if (rows.length) parts.push(`## Sheet: ${sheet.sheet || 'Sheet'}\n${rows.join('\n')}`);
  }
  return normalizeExtractedText(parts.join('\n\n'));
}

function formatSpreadsheetCell(value) {
  if (value == null) return '';
  if (value instanceof Date) return value.toISOString();
  return String(value).replace(/\r?\n/g, ' ').trim();
}

function normalizeExtractedText(value) {
  const noBom = String(value || '').replace(/^\uFEFF/, '');
  const normalized = noBom.replace(/\u0000/g, '').replace(/\r\n/g, '\n').trim();
  return truncateText(redactText(normalized, { maskEmails: true }), MAX_EXTRACTED_CHARS).trim();
}

function safePathSegment(value) {
  return String(value || '').replace(/[^a-zA-Z0-9_.-]+/g, '_').slice(0, 160) || 'session';
}

function isInsidePath(candidate, root) {
  const relative = path.relative(path.resolve(root), path.resolve(candidate));
  return relative === '' || (relative && !relative.startsWith('..') && !path.isAbsolute(relative));
}

function formatBytes(bytes) {
  const n = Number(bytes) || 0;
  if (n >= 1024 * 1024) return `${(n / (1024 * 1024)).toFixed(1)} MB`;
  if (n >= 1024) return `${(n / 1024).toFixed(1)} KB`;
  return `${n} B`;
}

function apiError(code, message, status = 500) {
  const error = new Error(message);
  error.code = code;
  error.status = status;
  return error;
}
