import assert from 'node:assert/strict';
import test from 'node:test';

import {
  buildAuthHeader,
  isAdminApiUrl,
  parseStoredToken,
  pickAuthToken,
  type StoredToken,
} from './tokenStore.ts';

const student: StoredToken = { token: 'user-token-value', tokenName: 'sa-token' };
const admin: StoredToken = { token: 'admin-token-value', tokenName: 'sa-token' };

test('isAdminApiUrl：仅 /api/admin 前缀算管理端接口', () => {
  assert.equal(isAdminApiUrl('/api/admin/users'), true);
  assert.equal(isAdminApiUrl('/api/admin/login'), true);
  assert.equal(isAdminApiUrl('/api/admin'), true);
  assert.equal(isAdminApiUrl('/api/admin?page=1'), true);
  assert.equal(isAdminApiUrl('/api/adminx/users'), false);
  assert.equal(isAdminApiUrl('/api/users'), false);
  assert.equal(isAdminApiUrl(undefined), false);
});

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

test('pickAuthToken：管理端接口取 admin，其余取学员', () => {
  assert.equal(pickAuthToken('/api/admin/users', student, admin), admin);
  assert.equal(pickAuthToken('/api/interview/sessions', student, admin), student);
  assert.equal(pickAuthToken('/api/auth/me', student, admin), student);
});

test('buildAuthHeader：按 URL 生成请求头，无登录态时为空对象', () => {
  assert.deepEqual(buildAuthHeader('/api/admin/users', student, admin), {
    'sa-token': 'admin-token-value',
  });
  assert.deepEqual(buildAuthHeader('/api/interview/sessions', student, admin), {
    'sa-token': 'user-token-value',
  });
  assert.deepEqual(buildAuthHeader('/api/interview/sessions', student, null), {
    'sa-token': 'user-token-value',
  });
  assert.deepEqual(buildAuthHeader('/api/admin/users', student, null), {});
  assert.deepEqual(buildAuthHeader('/api/interview/sessions', null, admin), {});
});

test('buildAuthHeader：保留后端回传的自定义 token 头名', () => {
  const custom: StoredToken = { token: 'abc', tokenName: 'x-user-token' };
  assert.deepEqual(buildAuthHeader('/api/users', custom, null), {
    'x-user-token': 'abc',
  });
});
