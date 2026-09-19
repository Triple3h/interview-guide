import assert from 'node:assert/strict';
import test from 'node:test';

import {
  buildAuthHeader,
  parseStoredToken,
  type StoredToken,
} from './tokenStore.ts';

const token: StoredToken = { token: 'user-token-value', tokenName: 'sa-token' };

test('parseStoredToken：解析存储的 JSON，缺 tokenName 用默认头名', () => {
  assert.deepEqual(parseStoredToken('{"token":"abc","tokenName":"sa-token"}'), {
    token: 'abc',
    tokenName: 'sa-token',
  });
  assert.deepEqual(parseStoredToken('{"token":"abc"}'), {
    token: 'abc',
    tokenName: 'sa-token',
  });
  assert.equal(parseStoredToken(null), null);
  assert.equal(parseStoredToken(''), null);
  assert.equal(parseStoredToken('not-json'), null);
  assert.equal(parseStoredToken('{"token":""}'), null);
});

test('buildAuthHeader：有登录态时生成请求头，无登录态时为空对象', () => {
  assert.deepEqual(buildAuthHeader(token), { 'sa-token': 'user-token-value' });
  assert.deepEqual(buildAuthHeader(null), {});
});

test('buildAuthHeader：保留后端回传的自定义 token 头名', () => {
  const custom: StoredToken = { token: 'abc', tokenName: 'x-user-token' };
  assert.deepEqual(buildAuthHeader(custom), { 'x-user-token': 'abc' });
});
