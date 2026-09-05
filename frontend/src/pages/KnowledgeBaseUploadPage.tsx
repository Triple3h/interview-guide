import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { motion } from 'framer-motion';
import {
  AlertCircle,
  ArrowLeft,
  CheckCircle2,
  ChevronRight,
  Clock,
  Copy,
  FileText,
  FolderOpen,
  Layers,
  Loader2,
  PlusCircle,
  Trash2,
  Upload,
} from 'lucide-react';
import {
  knowledgeBaseApi,
  type CreateKbBatchResponse,
} from '../api/knowledgebase';
import { getErrorMessage } from '../api/request';
import {
  buildBatchQueue,
  collectQueueKeys,
  extractFilesFromDataTransfer,
  removeQueueItems,
  summarizeQueue,
  type BatchFileInput,
  type BatchQueueItem,
  type BatchQueueItemStatus,
} from './knowledgeBaseBatchUpload';

interface KnowledgeBaseUploadPageProps {
  onBack: () => void;
  onViewProgress: (openBatchId: number) => void;
}

function formatFileSize(bytes: number): string {
  if (bytes === 0) return '0 B';
  const k = 1024;
  const sizes = ['B', 'KB', 'MB', 'GB'];
  const i = Math.floor(Math.log(bytes) / Math.log(k));
  return parseFloat((bytes / Math.pow(k, i)).toFixed(1)) + ' ' + sizes[i];
}

const STATUS_META: Record<BatchQueueItemStatus, { label: string; className: string }> = {
  waiting: { label: '等待上传', className: 'text-slate-500 dark:text-slate-400' },
  uploading: { label: '上传中', className: 'text-primary-600 dark:text-primary-400' },
  queued: { label: '已入队解析', className: 'text-green-600 dark:text-green-400' },
  duplicate: { label: '重复跳过', className: 'text-amber-600 dark:text-amber-400' },
  failed: { label: '失败', className: 'text-red-500 dark:text-red-400' },
  invalid: { label: '不参与上传', className: 'text-red-400 dark:text-red-500' },
};

function StatusBadge({ status }: { status: BatchQueueItemStatus }) {
  const meta = STATUS_META[status];
  return (
    <span className={`flex items-center gap-1.5 text-xs font-medium whitespace-nowrap ${meta.className}`}>
      {status === 'waiting' && <Clock className="w-3.5 h-3.5" />}
      {status === 'uploading' && <Loader2 className="w-3.5 h-3.5 animate-spin" />}
      {status === 'queued' && <CheckCircle2 className="w-3.5 h-3.5" />}
      {status === 'duplicate' && <Copy className="w-3.5 h-3.5" />}
      {(status === 'failed' || status === 'invalid') && <AlertCircle className="w-3.5 h-3.5" />}
      {meta.label}
    </span>
  );
}

export default function KnowledgeBaseUploadPage({ onBack, onViewProgress }: KnowledgeBaseUploadPageProps) {
  const [queue, setQueue] = useState<BatchQueueItem[]>([]);
  const [defaultCategory, setDefaultCategory] = useState('');
  const [singleName, setSingleName] = useState('');
  const [categories, setCategories] = useState<string[]>([]);
  const [uploading, setUploading] = useState(false);
  const [batch, setBatch] = useState<CreateKbBatchResponse | null>(null);
  const [startError, setStartError] = useState('');
  const [dragOver, setDragOver] = useState(false);

  const fileInputRef = useRef<HTMLInputElement>(null);
  const folderInputRef = useRef<HTMLInputElement>(null);

  useEffect(() => {
    knowledgeBaseApi
      .getAllCategories()
      .then(setCategories)
      .catch(err => console.error('加载分类失败:', err));
  }, []);

  const summary = useMemo(() => summarizeQueue(queue), [queue]);

  const addFiles = useCallback((inputs: BatchFileInput[]) => {
    if (inputs.length === 0) return;
    setQueue(prev => {
      const startId = prev.reduce((max, item) => Math.max(max, item.id), 0) + 1;
      return [...prev, ...buildBatchQueue(inputs, startId, collectQueueKeys(prev))];
    });
    setBatch(null);
  }, []);

  const handleFileInputChange = (e: React.ChangeEvent<HTMLInputElement>) => {
    const inputs = Array.from(e.target.files ?? []).map(file => ({
      file,
      relativePath: null,
    }));
    addFiles(inputs);
    e.target.value = '';
  };

  const handleFolderInputChange = (e: React.ChangeEvent<HTMLInputElement>) => {
    const inputs = Array.from(e.target.files ?? []).map(file => ({
      file,
      relativePath: (file as File & { webkitRelativePath?: string }).webkitRelativePath || null,
    }));
    addFiles(inputs);
    e.target.value = '';
  };

  const handleDrop = (e: React.DragEvent) => {
    e.preventDefault();
    setDragOver(false);
    if (uploading) return;
    const { files, hasFolder } = extractFilesFromDataTransfer(e.dataTransfer);
    addFiles(files);
    if (hasFolder) {
      setStartError('暂不支持拖拽文件夹，请使用「选择文件夹」按钮');
    }
  };

  // 逐个串行上传，避免瞬时并发压垮服务端
  const handleStartUpload = async () => {
    const pending = queue.filter(item => item.status === 'waiting');
    if (pending.length === 0 || uploading) return;

    setUploading(true);
    setStartError('');
    try {
      const created = await knowledgeBaseApi.createUploadBatch();
      setBatch(created);

      const isSingleFile = queue.length === 1;
      for (const item of pending) {
        setQueue(prev => prev.map(q => (q.id === item.id ? { ...q, status: 'uploading' } : q)));
        try {
          const result = await knowledgeBaseApi.uploadBatchFile(created.batchId, item.file, {
            category: item.category ?? defaultCategory.trim() ?? undefined,
            relativePath: item.relativePath ?? undefined,
            name: isSingleFile && singleName.trim() ? singleName.trim() : undefined,
          });
          setQueue(prev =>
            prev.map(q =>
              q.id === item.id ? { ...q, status: result.duplicate ? 'duplicate' : 'queued', error: null } : q
            )
          );
        } catch (err) {
          setQueue(prev =>
            prev.map(q => (q.id === item.id ? { ...q, status: 'failed', error: getErrorMessage(err) } : q))
          );
        }
      }
    } catch (err) {
      setStartError(getErrorMessage(err));
    } finally {
      setUploading(false);
    }
  };

  const handleRemove = (id: number) => {
    setQueue(prev => removeQueueItems(prev, [id]));
  };

  // 继续/重试：清掉已入队与重复跳过的行，失败项重置为待传，无效项保留标记
  const handleClearFinished = () => {
    setQueue(prev => prev
      .filter(item => item.status !== 'queued' && item.status !== 'duplicate')
      .map(item => (item.status === 'failed' ? { ...item, status: 'waiting', error: null } : item)));
    setBatch(null);
    setSingleName('');
  };

  const selectableCount = queue.filter(item => item.status === 'waiting').length;
  const singleFileName = queue.length === 1 ? queue[0].fileName : null;

  return (
    <div className="max-w-4xl mx-auto">
      {/* 页面标题 */}
      <div className="flex items-center justify-between mb-8">
        <div className="flex items-center gap-4">
          <button
            onClick={onBack}
            className="p-2 rounded-lg text-slate-500 dark:text-slate-400 hover:bg-slate-100 dark:hover:bg-slate-700 transition-colors"
            title="返回"
          >
            <ArrowLeft className="w-5 h-5" />
          </button>
          <div>
            <h1 className="text-2xl font-bold text-slate-800 dark:text-white flex items-center gap-3">
              <Upload className="w-7 h-7 text-primary-500" />
              上传知识库
            </h1>
            <p className="text-slate-500 dark:text-slate-400 mt-1">
              支持批量选择文件或整个文件夹，上传后进入服务端队列串行解析
            </p>
          </div>
        </div>
      </div>

      {/* 选择文件区域 */}
      <motion.div
        initial={{ opacity: 0, y: 20 }}
        animate={{ opacity: 1, y: 0 }}
        onDragOver={e => {
          e.preventDefault();
          if (!uploading) setDragOver(true);
        }}
        onDragLeave={() => setDragOver(false)}
        onDrop={handleDrop}
        className={`bg-white dark:bg-slate-800 rounded-2xl border-2 border-dashed p-8 text-center transition-colors ${
          dragOver
            ? 'border-primary-400 bg-primary-50/50 dark:bg-primary-900/10'
            : 'border-slate-200 dark:border-slate-600'
        }`}
      >
        <FileText className="w-12 h-12 text-slate-300 dark:text-slate-500 mx-auto mb-4" />
        <p className="text-slate-600 dark:text-slate-300 mb-1">拖拽文件到此处，或使用下方按钮选择</p>
        <p className="text-xs text-slate-400 dark:text-slate-500 mb-5">
          支持 PDF、DOCX、DOC、TXT、MD，单个文件最大 50MB；选择文件夹时将按第一级子文件夹自动分类
        </p>
        <div className="flex items-center justify-center gap-3">
          <button
            onClick={() => fileInputRef.current?.click()}
            disabled={uploading}
            className="flex items-center gap-2 px-4 py-2 bg-primary-500 text-white rounded-lg hover:bg-primary-600 transition-colors disabled:opacity-50"
          >
            <PlusCircle className="w-4 h-4" />
            选择文件
          </button>
          <button
            onClick={() => folderInputRef.current?.click()}
            disabled={uploading}
            className="flex items-center gap-2 px-4 py-2 bg-slate-100 dark:bg-slate-700 text-slate-700 dark:text-slate-200 rounded-lg hover:bg-slate-200 dark:hover:bg-slate-600 transition-colors disabled:opacity-50"
          >
            <FolderOpen className="w-4 h-4" />
            选择文件夹
          </button>
        </div>
        <input
          ref={fileInputRef}
          type="file"
          multiple
          accept=".pdf,.doc,.docx,.txt,.md"
          className="hidden"
          onChange={handleFileInputChange}
        />
        <input
          ref={folderInputRef}
          type="file"
          multiple
          className="hidden"
          onChange={handleFolderInputChange}
          {...{ webkitdirectory: '', directory: '' }}
        />
      </motion.div>

      {/* 默认分类与单文件命名 */}
      {queue.length > 0 && (
        <div className="bg-white dark:bg-slate-800 rounded-xl border border-slate-100 dark:border-slate-700 p-4 mt-4 flex flex-wrap items-center gap-4">
          <div className="flex-1 min-w-[200px]">
            <label className="block text-xs text-slate-500 dark:text-slate-400 mb-1">
              默认分类（未带子文件夹的文件使用）
            </label>
            <input
              type="text"
              value={defaultCategory}
              onChange={e => setDefaultCategory(e.target.value)}
              placeholder="留空则归入未分类"
              list="upload-category-suggestions"
              disabled={uploading}
              className="w-full px-3 py-2 text-sm border border-slate-200 dark:border-slate-600 rounded-lg focus:outline-none focus:ring-2 focus:ring-primary-500 bg-white dark:bg-slate-700 text-slate-900 dark:text-white disabled:opacity-50"
            />
            <datalist id="upload-category-suggestions">
              {categories.map(cat => (
                <option key={cat} value={cat} />
              ))}
            </datalist>
          </div>
          {singleFileName && (
            <div className="flex-1 min-w-[200px]">
              <label className="block text-xs text-slate-500 dark:text-slate-400 mb-1">
                知识库名称（可选）：{singleFileName}
              </label>
              <input
                type="text"
                value={singleName}
                onChange={e => setSingleName(e.target.value)}
                placeholder="留空则使用文件名"
                disabled={uploading}
                className="w-full px-3 py-2 text-sm border border-slate-200 dark:border-slate-600 rounded-lg focus:outline-none focus:ring-2 focus:ring-primary-500 bg-white dark:bg-slate-700 text-slate-900 dark:text-white disabled:opacity-50"
              />
            </div>
          )}
        </div>
      )}

      {/* 文件队列 */}
      {queue.length > 0 && (
        <div className="bg-white dark:bg-slate-800 rounded-xl border border-slate-100 dark:border-slate-700 overflow-hidden mt-4">
          <div className="flex items-center justify-between px-4 py-3 border-b border-slate-100 dark:border-slate-700">
            <div className="flex items-center gap-2 text-sm text-slate-600 dark:text-slate-300">
              <Layers className="w-4 h-4 text-primary-500" />
              共 {summary.total} 个文件
              {summary.invalid > 0 && (
                <span className="text-red-400 dark:text-red-500">（{summary.invalid} 个不可用）</span>
              )}
            </div>
            {!uploading && (
              <button
                onClick={() => {
                  setQueue([]);
                  setBatch(null);
                  setSingleName('');
                }}
                className="text-xs text-slate-400 hover:text-red-500 transition-colors"
              >
                清空列表
              </button>
            )}
          </div>
          <ul className="max-h-80 overflow-y-auto divide-y divide-slate-50 dark:divide-slate-700/50">
            {queue.map(item => (
              <li
                key={item.id}
                className="flex items-center gap-3 px-4 py-2.5 hover:bg-slate-50 dark:hover:bg-slate-700/40 transition-colors"
              >
                <FileText className="w-4 h-4 text-slate-400 shrink-0" />
                <div className="min-w-0 flex-1">
                  <p className="text-sm text-slate-800 dark:text-white truncate">{item.fileName}</p>
                  {(item.relativePath || item.error) && (
                    <p
                      className={`text-xs truncate ${
                        item.error
                          ? 'text-red-400 dark:text-red-500'
                          : 'text-slate-400 dark:text-slate-500'
                      }`}
                    >
                      {item.error ?? item.relativePath}
                    </p>
                  )}
                </div>
                <span className="text-xs text-slate-400 dark:text-slate-500 whitespace-nowrap">
                  {formatFileSize(item.file.size)}
                </span>
                {item.category && (
                  <span className="px-2 py-0.5 bg-slate-100 dark:bg-slate-700 text-slate-600 dark:text-slate-300 rounded text-xs whitespace-nowrap">
                    {item.category}
                  </span>
                )}
                <StatusBadge status={item.status} />
                {item.status !== 'uploading' && !uploading && (
                  <button
                    onClick={() => handleRemove(item.id)}
                    className="p-1 text-slate-300 hover:text-red-500 transition-colors"
                    title="移除"
                  >
                    <Trash2 className="w-3.5 h-3.5" />
                  </button>
                )}
              </li>
            ))}
          </ul>
        </div>
      )}

      {/* 错误提示 */}
      {startError && (
        <div className="flex items-center gap-2 bg-red-50 dark:bg-red-900/30 border border-red-200 dark:border-red-800 text-red-600 dark:text-red-400 rounded-lg px-4 py-3 mt-4 text-sm">
          <AlertCircle className="w-4 h-4 shrink-0" />
          {startError}
        </div>
      )}

      {/* 操作区 */}
      <div className="flex items-center justify-between gap-3 mt-6">
        <div className="text-sm text-slate-500 dark:text-slate-400">
          {uploading ? (
            <span className="flex items-center gap-2">
              <Loader2 className="w-4 h-4 animate-spin text-primary-500" />
              正在上传，请勿关闭页面…
            </span>
          ) : (
            summary.finished && (
              <span>
                已入队 {summary.queued + summary.duplicate} 个
                {summary.failed > 0 && <span className="text-red-500">，失败 {summary.failed} 个</span>}
              </span>
            )
          )}
        </div>
        <div className="flex items-center gap-3">
          {summary.finished && batch && (
            <>
              <button
                onClick={handleClearFinished}
                className="px-4 py-2 text-slate-600 dark:text-slate-300 hover:bg-slate-100 dark:hover:bg-slate-700 rounded-lg transition-colors text-sm"
              >
                继续上传
              </button>
              <button
                onClick={() => onViewProgress(batch.batchId)}
                className="flex items-center gap-2 px-4 py-2 bg-emerald-500 text-white rounded-lg hover:bg-emerald-600 transition-colors text-sm"
              >
                查看解析进度
                <ChevronRight className="w-4 h-4" />
              </button>
            </>
          )}
          {!summary.finished && (
            <button
              onClick={handleStartUpload}
              disabled={selectableCount === 0 || uploading}
              className="flex items-center gap-2 px-6 py-2.5 bg-gradient-to-r from-primary-500 to-primary-600 text-white rounded-lg hover:from-primary-600 hover:to-primary-700 shadow-lg shadow-primary-500/30 transition-all disabled:opacity-50 disabled:shadow-none"
            >
              {uploading ? <Loader2 className="w-4 h-4 animate-spin" /> : <Upload className="w-4 h-4" />}
              {uploading ? '上传中…' : `开始上传（${selectableCount} 个文件）`}
            </button>
          )}
        </div>
      </div>
    </div>
  );
}
