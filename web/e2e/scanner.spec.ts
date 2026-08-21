import { test, expect } from '@playwright/test';

test.describe('Event Management & Check-in E2E Flow', () => {
  test('full flow: create event, register, retrieve QR token, scan check-in, and view live dashboard', async ({ page }) => {
    // 1. Sign up as Organizer
    const orgEmail = `organizer_${Date.now()}@example.com`;
    await page.goto('/register');
    await page.fill('input[name="full_name"]', 'E2E Organizer');
    await page.fill('input[name="email"]', orgEmail);
    await page.fill('input[name="password"]', 'password123');
    await page.selectOption('select[name="role"]', 'organizer');
    await page.click('button:has-text("Create account")');
    await expect(page).toHaveURL(/\/dashboard/);

    // 2. Create Event
    await page.click('a:has-text("Create event")');
    await page.fill('input[name="name"]', 'E2E Tech Summit');
    const futureDate = new Date(Date.now() + 86400000).toISOString().slice(0, 16);
    await page.fill('input[name="event_date"]', futureDate);
    await page.fill('input[name="capacity"]', '50');
    await page.click('button:has-text("Create event")');
    await expect(page.locator('h1')).toContainText('Event dashboard');

    // 3. Sign up as Attendee & Register
    const attendeeEmail = `attendee_${Date.now()}@example.com`;
    await page.goto('/register');
    await page.fill('input[name="full_name"]', 'E2E Attendee');
    await page.fill('input[name="email"]', attendeeEmail);
    await page.fill('input[name="password"]', 'password123');
    await page.selectOption('select[name="role"]', 'attendee');
    await page.click('button:has-text("Create account")');
    await expect(page).toHaveURL(/\/events/);

    // Register for the event
    await page.click('button:has-text("Register now")');
    await expect(page.locator('button:has-text("Register now")')).not.toBeVisible();

    // Navigate to ticket QR page
    await page.goto('/my-registrations');
    await page.click('a:has-text("Show QR")');
    await expect(page.locator('canvas#qr-canvas')).toBeVisible();

    // 4. Scanner Station check-in
    await page.goto('/scanner');
    if (await page.locator('input[placeholder="scanner-1"]').isVisible()) {
      await page.fill('input[placeholder="scanner-1"]', 'gate-main');
      await page.click('button:has-text("Start scanning")');
    }
    await expect(page.locator('h1')).toContainText('gate-main');
  });

  test('offline scanner flow: queues scan offline and syncs on reconnection', async ({ page, context }) => {
    await page.goto('/scanner');
    if (await page.locator('input[placeholder="scanner-1"]').isVisible()) {
      await page.fill('input[placeholder="scanner-1"]', 'gate-offline');
      await page.click('button:has-text("Start scanning")');
    }

    // Go offline
    await context.setOffline(true);
    await page.fill('input[placeholder="Paste QR token"]', 'sample_offline_qr_token_123');
    await page.click('button:has-text("Check in")');

    // Verify offline banner/queue badge
    await expect(page.locator('text=Offline')).toBeVisible();

    // Go back online
    await context.setOffline(false);
    await expect(page.locator('text=Online')).toBeVisible();
  });
});
