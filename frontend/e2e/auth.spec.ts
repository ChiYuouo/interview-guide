import { randomUUID } from 'node:crypto';
import { expect, test } from '@playwright/test';

test('登录后返回原页面，刷新保持会话，退出后重新要求登录', async ({ page }) => {
  const password = randomUUID();
  let signedIn = false;
  const session = { username: 'admin', admin: true };
  const csrf = { headerName: 'X-CSRF-TOKEN', token: 'test-csrf-token' };

  await page.route('**/api/auth/session', route => signedIn
    ? route.fulfill({ json: { code: 200, message: 'success', data: session } })
    : route.fulfill({ status: 401, json: { code: 401, message: '请先登录', data: null } }));
  await page.route('**/api/auth/csrf', route => route.fulfill({
    json: { code: 200, message: 'success', data: csrf },
  }));
  await page.route('**/api/auth/login', route => {
    expect(route.request().headers()['x-csrf-token']).toBe(csrf.token);
    const credentials = route.request().postDataJSON();
    signedIn = credentials.username === session.username && credentials.password === password;
    return route.fulfill({ json: signedIn
      ? { code: 200, message: 'success', data: session }
      : { code: 401, message: '账号或密码错误', data: null } });
  });
  await page.route('**/api/auth/logout', route => {
    expect(route.request().headers()['x-csrf-token']).toBe(csrf.token);
    signedIn = false;
    return route.fulfill({ json: { code: 200, message: 'success', data: null } });
  });
  await page.route('**/api/interview-schedule**', route => route.fulfill({
    json: { code: 200, message: 'success', data: [] },
  }));

  await page.goto('/interview-schedule');
  await expect(page).toHaveURL(/\/login$/);
  await page.getByLabel('账号', { exact: true }).fill(session.username);
  await page.getByLabel('密码', { exact: true }).fill('wrong-password');
  await page.getByRole('button', { name: '登录', exact: true }).click();
  await expect(page.getByRole('alert')).toHaveText('账号或密码错误');

  await page.getByLabel('密码', { exact: true }).fill(password);
  await page.getByRole('button', { name: '登录', exact: true }).click();
  await expect(page).toHaveURL(/\/interview-schedule$/);
  await expect(page.getByText(session.username, { exact: true })).toBeVisible();
  await page.reload();
  await expect(page.getByRole('button', { name: '退出登录' })).toBeVisible();
  await page.getByRole('button', { name: '退出登录' }).click();
  await expect(page).toHaveURL(/\/login$/);
  await expect(page.getByRole('heading', { name: '登录面试工作台' })).toBeVisible();
});

test('普通账号可以打开知识库、面试和个人模型设置', async ({ page }) => {
  await page.route('**/api/auth/session', route => route.fulfill({
    json: { code: 200, message: 'success', data: { username: 'trial', admin: false } },
  }));
  await page.route('**/api/interview-schedule**', route => route.fulfill({
    json: { code: 200, message: 'success', data: [] },
  }));
  await page.route('**/api/resumes', route => route.fulfill({
    json: { code: 200, message: 'success', data: [] },
  }));
  await page.route('**/api/llm-provider/**', route => route.fulfill({
    json: { code: 200, message: 'success', data: [] },
  }));
  await page.route('**/api/interview/**', route => route.fulfill({
    json: { code: 200, message: 'success', data: [] },
  }));
  await page.route('**/api/voice-interview/sessions**', route => route.fulfill({
    json: { code: 200, message: 'success', data: [] },
  }));
  await page.route('**/api/knowledgebase/**', route => route.fulfill({
    json: { code: 200, message: 'success', data: [] },
  }));
  await page.route('**/api/knowledgebase/stats', route => route.fulfill({
    json: { code: 200, message: 'success', data: {
      totalCount: 0, totalQuestionCount: 0, totalAccessCount: 0, completedCount: 0, processingCount: 0,
    } },
  }));
  await page.route('**/api/rag-chat/sessions', route => route.fulfill({
    json: { code: 200, message: 'success', data: [] },
  }));

  await page.goto('/knowledgebase');
  await expect(page).toHaveURL(/\/knowledgebase$/);
  await expect(page.getByRole('heading', { name: '知识库管理', exact: true })).toBeVisible();
  await page.getByRole('link', { name: /知识库面试/ }).click();
  await expect(page).toHaveURL(/\/knowledgebase-interview$/);
  await page.getByRole('link', { name: /问答助手/ }).click();
  await expect(page).toHaveURL(/\/knowledgebase\/chat$/);
  await page.goto('/settings');
  await expect(page).toHaveURL(/\/settings$/);
  await expect(page.getByRole('heading', { name: '系统设置', exact: true })).toBeVisible();
  await expect(page.getByRole('link', { name: /设置/ })).toHaveCount(1);
  await expect(page.getByRole('tab', { name: '系统配置' })).toHaveCount(0);
  await page.getByRole('link', { name: /简历管理/ }).click();
  await expect(page).toHaveURL(/\/history$/);
  await expect(page.getByRole('heading', { name: '简历管理' })).toBeVisible();
  await page.getByRole('button', { name: '模拟面试' }).click();
  await expect(page).toHaveURL(/\/interview-hub$/);
  await expect(page.getByRole('button', { name: '开始文字面试' })).toBeVisible();
  await page.getByRole('button', { name: /^语音面试/ }).click();
  await expect(page.getByRole('button', { name: '开始语音面试' })).toBeVisible();
  await page.getByRole('link', { name: /面试记录/ }).click();
  await expect(page).toHaveURL(/\/interviews$/);
  await expect(page.getByRole('button', { name: '语音面试', exact: true })).toBeVisible();
  await page.goto('/voice-interview');
  await expect(page).toHaveURL(/\/voice-interview$/);
});
