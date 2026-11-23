import { expect, test } from '@playwright/test';
import type { Page } from '@playwright/test';
import type { ProviderItem } from '../src/types/llmProvider';

async function settingsFixture(page: Page, admin = false) {
  let providers: ProviderItem[] = ['lmstudio', 'kimi', 'deepseek', 'glm', 'dashscope'].map(id => ({
    id, baseUrl: `https://example.com/${id}/v1`, model: `${id}-chat`,
    maskedApiKey: '未配置', apiKeyConfigured: false, temperature: 0.2,
    supportsEmbedding: false, embeddingModel: null, embeddingDimensions: null,
    defaultChatProvider: false, defaultEmbeddingProvider: false,
  }));
  let defaultProvider: string | null = null;
  const keys = new Map<string, string>();
  const requests: string[] = [];
  const voice: Record<string, Record<string, unknown>> = {
    asr: { model: 'own-asr', url: 'wss://example.com/asr', language: 'zh', format: 'pcm', sampleRate: 16000, maskedApiKey: '未配置' },
    tts: { model: 'own-tts', url: 'wss://example.com/tts', voice: 'Cherry', format: 'pcm', sampleRate: 24000, mode: 'commit', languageType: 'Chinese', speechRate: 1, volume: 60, maskedApiKey: '未配置' },
  };
  await page.route('**/api/auth/session', route => route.fulfill({
    json: { code: 200, message: 'success', data: { username: admin ? 'admin' : 'ordinary-user', admin } },
  }));
  await page.route('**/api/auth/csrf', route => route.fulfill({
    json: { code: 200, message: 'success', data: { headerName: 'X-CSRF-TOKEN', token: 'test' } },
  }));
  await page.route('**/api/llm-provider**', async route => {
    const request = route.request();
    const path = new URL(request.url()).pathname;
    const id = path.split('/')[3];
    requests.push(path);
    let data: unknown = null;
    if (request.method() !== 'GET') expect(request.headers()['x-csrf-token']).toBe('test');
    if (path.includes('/voice/')) {
      const kind = path.includes('/asr') ? 'asr' : 'tts';
      if (request.method() === 'PUT') {
        const { apiKey, ...fields } = request.postDataJSON();
        if (apiKey) keys.set(kind, apiKey);
        voice[kind] = { ...voice[kind], ...fields, maskedApiKey: keys.has(kind) ? '已配置' : '未配置' };
      }
      data = voice[kind];
    } else if (path.endsWith('/list')) data = providers;
    else if (path.endsWith('/default-provider')) {
      if (request.method() === 'PUT') {
        defaultProvider = request.postDataJSON().defaultProvider;
        providers = providers.map(provider => ({ ...provider, defaultChatProvider: provider.id === defaultProvider }));
      }
      data = { defaultProvider, defaultEmbeddingProvider: null };
    } else if (path.endsWith('/test')) {
      expect(keys.get(id)).toBe('browser-test-key');
      data = { success: true, message: '连接成功', model: 'chat-model' };
    } else if (request.method() === 'DELETE') {
      providers = providers.filter(provider => provider.id !== id);
    } else {
      const body = request.postDataJSON();
      const modelId = id ?? body.id;
      if (body.apiKey) keys.set(modelId, body.apiKey);
      const existing = providers.find(provider => provider.id === modelId);
      providers = [...providers.filter(provider => provider.id !== modelId), {
        ...existing, id: modelId, baseUrl: body.baseUrl, model: body.model,
        temperature: body.temperature ?? 0.2, apiKeyConfigured: keys.has(modelId),
        maskedApiKey: keys.has(modelId) ? '已配置' : '未配置',
        supportsEmbedding: false, embeddingModel: null, embeddingDimensions: null,
        defaultChatProvider: modelId === defaultProvider, defaultEmbeddingProvider: false,
      }];
    }
    await route.fulfill({ json: { code: 200, message: 'success', data } });
  });
  return { keys, requests };
}

test('缺少模型配置时提供跳转原设置页的入口', async ({ page }) => {
  await settingsFixture(page);
  await page.route('**/api/interview/skills', route => route.fulfill({
    json: { code: 200, message: 'success', data: [] },
  }));
  await page.route('**/api/voice-interview/sessions', route => route.fulfill({
    json: { code: 11005, message: '请先配置模型和 API Key', data: null },
  }));
  await page.goto('/voice-interview?skillId=java-backend&duration=15');
  await expect(page.getByText('请先配置模型和 API Key', { exact: true }).first()).toBeVisible();
  await page.getByRole('link', { name: '前往设置', exact: true }).first().click();
  await expect(page).toHaveURL(/\/settings$/);
  await expect(page.getByRole('heading', { name: '系统设置', exact: true })).toBeVisible();
});

test('普通用户在原设置页编辑默认卡片，通过原接口保存URL和Key', async ({ page }) => {
  const fixture = await settingsFixture(page);
  await page.goto('/settings');
  await expect(page.getByRole('article')).toHaveCount(7);
  await expect(page.getByRole('article').getByText('未配置', { exact: true })).toHaveCount(7);
  await expect(page.getByRole('tab')).toHaveCount(0);
  await expect(page.getByRole('link', { name: /我的模型/ })).toHaveCount(0);
  const card = page.getByRole('article', { name: 'deepseek', exact: true });
  await expect(card.getByRole('button', { name: '测试', exact: true })).toBeDisabled();
  await card.getByRole('button', { name: '编辑', exact: true }).click();
  await expect(page.getByLabel('Provider ID')).toBeDisabled();
  await expect(page.getByLabel('API Key')).toHaveValue('');
  await page.getByLabel('Base URL').fill('https://my-model.example.com/v1');
  await page.getByLabel('API Key').fill('browser-test-key');
  await page.getByRole('dialog').getByRole('button', { name: '保存', exact: true }).click();
  await expect(card.getByText('已配置', { exact: true })).toBeVisible();
  await card.getByRole('button', { name: '测试', exact: true }).click();
  await expect(card.getByText('连接成功')).toBeVisible();
  await card.getByRole('button', { name: '设为文字' }).click();
  await page.getByRole('button', { name: '确认设置' }).click();
  await expect(card.getByText('文字默认', { exact: true })).toBeVisible();
  await card.getByRole('button', { name: '编辑', exact: true }).click();
  await expect(page.getByLabel('API Key')).toHaveValue('');
  await page.getByRole('dialog').getByRole('button', { name: '保存', exact: true }).click();
  await expect(page.getByRole('dialog')).toHaveCount(0);
  expect(fixture.keys.get('deepseek')).toBe('browser-test-key');
  expect(fixture.requests.some(path => path.includes('/voice/'))).toBe(true);
  await page.reload();
  await expect(card.getByText('https://my-model.example.com/v1', { exact: true })).toBeVisible();
  await page.screenshot({ path: 'test-results/settings-isolated-providers.png', fullPage: true });
});

test('普通用户复用原新增Provider弹窗和删除接口', async ({ page }) => {
  await settingsFixture(page);
  await page.goto('/settings');
  await page.getByRole('button', { name: '新增 Provider', exact: true }).click();
  await page.getByLabel('Provider ID').fill('custom-provider');
  await page.getByLabel('Base URL').fill('https://example.com/v1');
  await page.getByLabel('聊天模型', { exact: false }).fill('custom-chat');
  await page.getByLabel('API Key').fill('browser-test-key');
  await expect(page.getByText('支持 Embedding', { exact: true })).toBeVisible();
  await page.getByRole('dialog').getByRole('button', { name: '保存', exact: true }).click();
  const card = page.getByRole('article', { name: 'custom-provider', exact: true });
  await expect(card).toBeVisible();
  await card.getByRole('button', { name: '删除', exact: true }).click();
  await page.getByRole('button', { name: '确定删除', exact: true }).click();
  await expect(card).toHaveCount(0);
});

test('管理员保留原模型配置和语音服务，不增加独立页面或标签', async ({ page }) => {
  await settingsFixture(page, true);
  await page.goto('/settings');
  await expect(page.getByRole('heading', { name: '语音服务' })).toBeVisible();
  await expect(page.getByRole('tab')).toHaveCount(0);
  await page.getByRole('article', { name: 'deepseek', exact: true }).getByRole('button', { name: '编辑' }).click();
  await expect(page.getByText('支持 Embedding', { exact: true })).toBeVisible();
  await expect(page.getByLabel('Provider ID')).toBeDisabled();
});

test('普通用户能配置自己的ASR和TTS，二者密钥分别保存且不回填', async ({ page }) => {
  const fixture = await settingsFixture(page);
  await page.goto('/settings');
  const asr = page.getByRole('article', { name: 'ASR 语音识别', exact: true });
  const tts = page.getByRole('article', { name: 'TTS 语音合成', exact: true });
  await asr.getByRole('button', { name: '编辑', exact: true }).click();
  await page.getByPlaceholder('留空则保持原值').fill('own-asr-key');
  await page.getByRole('button', { name: '保存', exact: true }).click();
  await expect(asr.getByText('已配置', { exact: true })).toBeVisible();
  await expect(tts.getByText('未配置', { exact: true })).toBeVisible();
  await tts.getByRole('button', { name: '编辑', exact: true }).click();
  await expect(page.getByPlaceholder('留空则保持原值')).toHaveValue('');
  await page.getByLabel('WebSocket URL').fill('wss://own.example.com/tts');
  await page.getByPlaceholder('留空则保持原值').fill('own-tts-key');
  await page.getByRole('button', { name: '保存', exact: true }).click();
  await expect(tts.getByText('已配置', { exact: true })).toBeVisible();
  expect(fixture.keys.get('asr')).toBe('own-asr-key');
  expect(fixture.keys.get('tts')).toBe('own-tts-key');
  await asr.getByRole('button', { name: '编辑', exact: true }).click();
  await expect(page.getByPlaceholder('留空则保持原值')).toHaveValue('');
});
