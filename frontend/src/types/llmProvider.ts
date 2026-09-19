export interface ProviderItem {
  id: string;
  baseUrl: string;
  maskedApiKey: string;
  model: string;
  embeddingModel: string | null;
  embeddingDimensions: number | null;
  supportsEmbedding: boolean;
  temperature: number | null;
  defaultChatProvider: boolean;
  defaultEmbeddingProvider: boolean;
}

export interface CreateProviderRequest {
  id: string;
  baseUrl: string;
  apiKey: string;
  model: string;
  embeddingModel?: string;
  embeddingDimensions?: number;
  supportsEmbedding?: boolean;
  temperature?: number;
}

export interface UpdateProviderRequest {
  baseUrl?: string;
  apiKey?: string;
  model?: string;
  embeddingModel?: string;
  embeddingDimensions?: number;
  supportsEmbedding?: boolean;
  temperature?: number;
}

export interface ProviderTestResult {
  success: boolean;
  message: string;
  model: string;
}

export interface DefaultProvider {
  defaultProvider: string;
  defaultEmbeddingProvider: string;
}

export type VoiceProvider = 'dashscope' | 'volcengine';

export interface DashscopeAsrConfig {
  url: string;
  model: string;
  maskedApiKey: string;
  language: string;
  format: string;
  sampleRate: number;
  enableTurnDetection: boolean;
  turnDetectionType: string;
  turnDetectionThreshold: number;
  turnDetectionSilenceDurationMs: number;
}

export interface VolcengineAsrConfig {
  url: string;
  resourceId: string;
  modelName: string;
  maskedApiKey: string;
  format: string;
  sampleRate: number;
  bits: number;
  channel: number;
  enableItn: boolean;
  enablePunc: boolean;
  enableDdc: boolean;
  enableNonstream: boolean;
  segmentMs: number;
}

export interface AsrConfig {
  provider: VoiceProvider;
  dashscope: DashscopeAsrConfig;
  volcengine: VolcengineAsrConfig;
}

export interface DashscopeTtsConfig {
  model: string;
  maskedApiKey: string;
  voice: string;
  format: string;
  sampleRate: number;
  mode: string;
  languageType: string;
  speechRate: number;
  volume: number;
}

export interface VolcengineTtsConfig {
  url: string;
  resourceId: string;
  speaker: string;
  maskedApiKey: string;
  format: string;
  sampleRate: number;
}

export interface TtsConfig {
  provider: VoiceProvider;
  dashscope: DashscopeTtsConfig;
  volcengine: VolcengineTtsConfig;
}

export interface DashscopeAsrConfigRequest {
  url?: string;
  model?: string;
  apiKey?: string;
  language?: string;
  format?: string;
  sampleRate?: number;
  enableTurnDetection?: boolean;
  turnDetectionType?: string;
  turnDetectionThreshold?: number;
  turnDetectionSilenceDurationMs?: number;
}

export interface VolcengineAsrConfigRequest {
  url?: string;
  apiKey?: string;
  resourceId?: string;
  modelName?: string;
  format?: string;
  sampleRate?: number;
  bits?: number;
  channel?: number;
  enableItn?: boolean;
  enablePunc?: boolean;
  enableDdc?: boolean;
  enableNonstream?: boolean;
  segmentMs?: number;
}

export interface AsrConfigRequest {
  provider?: VoiceProvider;
  dashscope?: DashscopeAsrConfigRequest;
  volcengine?: VolcengineAsrConfigRequest;
}

export interface DashscopeTtsConfigRequest {
  model?: string;
  apiKey?: string;
  voice?: string;
  format?: string;
  sampleRate?: number;
  mode?: string;
  languageType?: string;
  speechRate?: number;
  volume?: number;
}

export interface VolcengineTtsConfigRequest {
  url?: string;
  apiKey?: string;
  resourceId?: string;
  speaker?: string;
  format?: string;
  sampleRate?: number;
}

export interface TtsConfigRequest {
  provider?: VoiceProvider;
  dashscope?: DashscopeTtsConfigRequest;
  volcengine?: VolcengineTtsConfigRequest;
}
