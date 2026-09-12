import assert from 'node:assert/strict';
import test from 'node:test';

import { buildVoiceInterviewWsUrl } from './voiceInterviewWs.ts';

test('http 站点推导 ws://，https 站点推导 wss://', () => {
  assert.equal(
    buildVoiceInterviewWsUrl(12, { protocol: 'http:', host: 'example.com:18080' }),
    'ws://example.com:18080/ws/voice-interview/12',
  );
  assert.equal(
    buildVoiceInterviewWsUrl(12, { protocol: 'https:', host: 'example.com' }),
    'wss://example.com/ws/voice-interview/12',
  );
});

test('带 token 时追加 ?token= 并做 URL 编码', () => {
  assert.equal(
    buildVoiceInterviewWsUrl(7, { protocol: 'http:', host: 'localhost:5173', token: 'abc/def+==' }),
    'ws://localhost:5173/ws/voice-interview/7?token=abc%2Fdef%2B%3D%3D',
  );
});

test('无 token 时不带查询参数', () => {
  assert.equal(
    buildVoiceInterviewWsUrl(3, { protocol: 'http:', host: 'localhost:8080' }),
    'ws://localhost:8080/ws/voice-interview/3',
  );
  assert.equal(
    buildVoiceInterviewWsUrl(3, { protocol: 'http:', host: 'localhost:8080', token: '' }),
    'ws://localhost:8080/ws/voice-interview/3',
  );
});

test('VITE_WS_BASE_URL 覆盖站点推导，并容忍结尾斜杠', () => {
  assert.equal(
    buildVoiceInterviewWsUrl(5, { protocol: 'http:', host: 'x', wsBaseUrl: 'wss://api.example.com/' }),
    'wss://api.example.com/ws/voice-interview/5',
  );
});
