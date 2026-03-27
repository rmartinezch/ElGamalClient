const { test, expect } = require('@playwright/test');

async function expectOperationOk(page, name) {
  await expect(page.locator('#heroStatus')).toContainText(name);
  await expect(page.locator('#heroStatus')).toContainText('ok');
}

async function clickOperation(sourcePage, adminPage, label, name) {
  await sourcePage.getByRole('button', { name: label }).click();
  await expect(sourcePage.locator('#partyCurrentOperation')).toContainText(name);
  await expectOperationOk(adminPage, name);
}

async function runSequentialOperation(pages, label, name, adminPage) {
  for (let index = 0; index < pages.length; index += 1) {
    const page = pages[index];
    await expect(page.getByRole('button', { name: label })).toBeEnabled();
    await page.getByRole('button', { name: label }).click();
    await expect(page.locator('#partyCurrentOperation')).toContainText(name);
    if (index + 1 < pages.length) {
      await expect(pages[index + 1].getByRole('button', { name: label })).toBeEnabled();
    }
  }
  await expectOperationOk(adminPage, name);
}

async function expectPartyConsole(page, text) {
  await expect(page.locator('#partyConsole')).toContainText(text);
}

test('recorre el flujo completo de la GUI', async ({ page }) => {
  await page.goto('/');

  const discoveryBeforeRes = await page.request.get('http://127.0.0.1:4170/api/discovery');
  expect(discoveryBeforeRes.ok()).toBeTruthy();
  const discoveryBefore = await discoveryBeforeRes.json();
  expect(discoveryBefore.server.has_active_session).toBeFalsy();
  expect(discoveryBefore.server.accepting_votes).toBeFalsy();

  await expect(page.getByRole('heading', { name: 'Servidor 1 define las parties y crea la llave' })).toBeVisible();
  await expect(page.locator('#heroStatus')).toContainText('Sin sesión activa');
  await expect(page.getByRole('heading', { name: 'Sesión activa' })).toHaveCount(0);
  await expect(page.getByRole('heading', { name: 'Ventanas y parties' })).toHaveCount(0);
  await expect(page.locator('.party-card')).toHaveCount(0);

  const party1 = await page.context().newPage();
  const party2 = await page.context().newPage();
  const party3 = await page.context().newPage();
  await party1.goto('http://127.0.0.1:4171');
  await party2.goto('http://127.0.0.1:4172');
  await party3.goto('http://127.0.0.1:4173');
  await expect(party1.locator('#partyStatus')).toContainText('Sin sesión activa');
  await expect(party2.locator('#partyStatus')).toContainText('Sin sesión activa');
  await expect(party3.locator('#partyStatus')).toContainText('Sin sesión activa');

  await page.locator('#label').fill('Servidor 1 QA');
  await page.locator('#election_name').fill('Elección Demo QA');
  await page.locator('#sid').fill('ONPE-QA');
  await page.locator('#host').fill('127.0.0.1');
  await page.locator('#parties').fill('3');
  await page.locator('#threshold').fill('2');

  await page.getByRole('button', { name: 'Nueva sesión y ejecutar keygen' }).click();

  await expect(page.locator('#heroStatus')).toContainText('3 parties');
  await expect(page.locator('.party-card')).toHaveCount(3);
  await expectOperationOk(page, 'keygen');
  await expect(page.locator('#dynamicFrames')).toContainText('/runtime-e2e/sessions/');
  await expect(page.locator('#dynamicFrames')).toContainText('party01/publicKey');
  await expect(page.locator('.party-card').first()).toContainText('Ventana: 4171');
  await expect(page.locator('.party-card').nth(1)).toContainText('Party HTTP: 127.0.0.1:8042');
  await expect(page.locator('.party-card').first().locator('.chip.on').filter({ hasText: 'publicKey_ext' })).toBeVisible();
  const publicKeyRes = await page.request.get('http://127.0.0.1:4170/api/public-key');
  expect(publicKeyRes.ok()).toBeTruthy();
  const publicKeyPayload = await publicKeyRes.json();
  expect(publicKeyPayload.content).toContain('mock-public-key-native');
  expect(publicKeyPayload.session_name).toBe('Servidor 1 QA');
  const emissionContextRes = await page.request.get('http://127.0.0.1:4170/api/emission-context');
  expect(emissionContextRes.ok()).toBeTruthy();
  const emissionContextPayload = await emissionContextRes.json();
  expect(emissionContextPayload.session_id).toBe(publicKeyPayload.session_id);
  expect(emissionContextPayload.session_name).toBe(publicKeyPayload.session_name);
  expect(emissionContextPayload.auxsid).toBe('default');
  expect(emissionContextPayload.resolved_auxsid).toBe('default');
  expect(emissionContextPayload.accumulated).toBeFalsy();
  const discoveryRes = await page.request.get('http://127.0.0.1:4170/api/discovery');
  expect(discoveryRes.ok()).toBeTruthy();
  const discoveryPayload = await discoveryRes.json();
  expect(discoveryPayload.server.has_active_session).toBeTruthy();
  expect(discoveryPayload.server.accepting_votes).toBeTruthy();
  expect(discoveryPayload.session.session_id).toBe(publicKeyPayload.session_id);
  const auxsidsInitialRes = await page.request.get('http://127.0.0.1:4170/api/auxsids');
  expect(auxsidsInitialRes.ok()).toBeTruthy();
  const auxsidsInitialPayload = await auxsidsInitialRes.json();
  expect(auxsidsInitialPayload.suggested_auxsid).toBe(emissionContextPayload.auxsid);
  expect(auxsidsInitialPayload.reserved_auxsids).not.toContain('ext');
  expect(auxsidsInitialPayload.reserved_auxsids).not.toContain('orig');
  const handshakeRes = await page.request.post('http://127.0.0.1:4170/api/handshake', {
    data: {
      station_id: 'mesa-qa-01',
      session_id: publicKeyPayload.session_id,
      session_name: publicKeyPayload.session_name,
      auxsid: 'default',
    },
  });
  expect(handshakeRes.ok()).toBeTruthy();
  const handshakePayload = await handshakeRes.json();
  expect(handshakePayload.accepted).toBeTruthy();
  expect(handshakePayload.station_id).toBe('mesa_qa_01');
  expect(handshakePayload.session.session_id).toBe(publicKeyPayload.session_id);
  expect(handshakePayload.lease_id).toContain('mesa_qa_01');
  const handshakesRes = await page.request.get('http://127.0.0.1:4170/api/handshakes');
  expect(handshakesRes.ok()).toBeTruthy();
  const handshakesPayload = await handshakesRes.json();
  expect(handshakesPayload.stations).toHaveLength(1);
  expect(handshakesPayload.stations[0].station_id).toBe('mesa_qa_01');
  await party1.reload();
  await party2.reload();
  await party3.reload();
  await expect(party1.locator('#partyTitle')).toContainText('Party 1');
  await expect(party2.locator('#partyTitle')).toContainText('Party 2');
  await expect(party3.locator('#partyTitle')).toContainText('Party 3');
  await expect(party1.locator('#auxsidInput')).toHaveValue(emissionContextPayload.auxsid);
  await expect(party1.locator('#partySummary')).toContainText('8041');
  await expect(party2.locator('#partySummary')).toContainText('8042');
  await expect(party3.locator('#partySummary')).toContainText('8043');
  await expectPartyConsole(party1, 'vmn -keygen -e publicKey');
  await expectPartyConsole(party2, 'vmnc -pkey -outi native protInfo.xml publicKey publicKey_ext');
  await expect(page.getByRole('button', { name: 'Precomputación' })).toHaveCount(0);
  await expect(party1.getByRole('button', { name: 'Precomputación' })).toBeVisible();
  await expect(party1.locator('#precompNote')).toContainText('La precomputación es opcional');
  await expect(party2.locator('#partyActionsRow')).toBeVisible();
  await expect(party3.locator('#partyActionsRow')).toBeVisible();
  await expect(party1.getByRole('button', { name: 'Precomputación' })).toBeEnabled();
  await expect(party2.getByRole('button', { name: 'Precomputación' })).toBeDisabled();
  await expect(party3.getByRole('button', { name: 'Precomputación' })).toBeDisabled();
  await expect(party1.locator('[data-phase="precomp"]')).toContainText('mi turno');
  await expect(party2.locator('[data-phase="precomp"]')).toContainText('esperando party anterior');

  await runSequentialOperation([party1, party2, party3], 'Precomputación', 'precomp', page);
  await expectPartyConsole(party1, 'vmn -precomp -e -maxciph 100');
  await expectPartyConsole(party2, 'precomputation ready');
  await expect(party1.locator('[data-phase="precomp"]')).toContainText('completada');
  await expect(party3.locator('[data-phase="precomp"]')).toContainText('completada');

  const uploadRes = await page.request.post('http://127.0.0.1:4170/api/ciphertexts', {
    data: {
      ciphertexts: 'mock-uploaded-ciphertexts\n',
      auxsid: 'default',
      format: 'native',
      session_id: publicKeyPayload.session_id,
      session_name: publicKeyPayload.session_name,
      station_id: handshakePayload.station_id,
      lease_id: handshakePayload.lease_id,
    },
  });
  expect(uploadRes.ok()).toBeTruthy();
  const uploadPayload = await uploadRes.json();
  expect(uploadPayload.resolved_auxsid).toBe('default');
  expect(uploadPayload.validated).toBeTruthy();
  expect(uploadPayload.format_received).toBe('native');
  expect(uploadPayload.party_validated).toBe('party01');
  expect(uploadPayload.session_id).toBe(publicKeyPayload.session_id);
  expect(uploadPayload.session_name).toBe(publicKeyPayload.session_name);
  expect(uploadPayload.accumulated).toBeFalsy();
  expect(uploadPayload.handshake_validated).toBeTruthy();
  expect(uploadPayload.station_id).toBe(handshakePayload.station_id);
  expect(uploadPayload.lease_id).toBe(handshakePayload.lease_id);
  expect(uploadPayload.replicated_to).toEqual(['party01', 'party02', 'party03']);
  expect(uploadPayload.replicated_files.ciphertexts_ext.length).toBe(3);
  expect(uploadPayload.replicated_files.ciphertexts.length).toBe(3);
  await expect(page.locator('#eventLog')).toContainText('Ciphertexts validados y cargados vía API para AuxSID default con formato native');
  await expect(party1.locator('#auxsidStatus')).toContainText('pendientes: default');

  const uploadResAccumulated = await page.request.post('http://127.0.0.1:4170/api/ciphertexts', {
    data: {
      ciphertexts: 'mock-uploaded-ciphertexts-append\n',
      auxsid: 'default',
      format: 'native',
      session_id: publicKeyPayload.session_id,
    },
  });
  expect(uploadResAccumulated.ok()).toBeTruthy();
  const uploadPayloadAccumulated = await uploadResAccumulated.json();
  expect(uploadPayloadAccumulated.resolved_auxsid).toBe('default');
  expect(uploadPayloadAccumulated.auxsid_changed).toBeFalsy();
  expect(uploadPayloadAccumulated.accumulated).toBeTruthy();
  expect(uploadPayloadAccumulated.accumulated_from_auxsid).toBe('default');
  await expect(page.locator('#eventLog')).toContainText('Carga acumulada sobre ciphertexts previos');

  await expect(party2.getByRole('button', { name: 'Mezclar' })).toBeDisabled();
  await expect(party1.locator('[data-phase="shuffle"]')).toContainText('mi turno');
  await runSequentialOperation([party1, party2, party3], 'Mezclar', 'shuffle', page);
  await expect(page.locator('.party-card').first().locator('.chip.on').filter({ hasText: 'ciphertextsout' })).toBeVisible();
  await expectPartyConsole(party2, 'vmn -shuffle privInfo.xml protInfo.xml ciphertexts ciphertextsout');
  await expect(party2.locator('[data-phase="shuffle"]')).toContainText('completada');
  await expect(party2.locator('#plaintextsDownloadRow')).toBeHidden();

  await runSequentialOperation([party1, party2, party3], 'Descifrar', 'decrypt', page);
  await expect(page.locator('.party-card').first().locator('.chip.on').filter({ hasText: 'plaintexts' })).toBeVisible();
  await expect(page.locator('.party-card').nth(2).locator('.chip.on').filter({ hasText: 'plaintexts' })).toBeVisible();
  await expectPartyConsole(party1, 'vmn -decrypt privInfo.xml protInfo.xml ciphertextsout plaintexts_orig');
  await expect(party3.locator('[data-phase="decrypt"]')).toContainText('completada');
  await expect(party2.locator('#plaintextsDownloadRow')).toBeVisible();
  await expect(party2.locator('#downloadStatus')).toContainText('Descarga disponible para AuxSID default desde party02');
  await expect(party2.locator('#downloadPlaintextsBtn')).toBeEnabled();
  const plaintextsRes = await page.request.get('http://127.0.0.1:4172/api/plaintexts?auxsid=default');
  expect(plaintextsRes.ok()).toBeTruthy();
  const plaintextsPayload = await plaintextsRes.json();
  expect(plaintextsPayload.session_id).toBe(publicKeyPayload.session_id);
  expect(plaintextsPayload.session_name).toBe(publicKeyPayload.session_name);
  expect(plaintextsPayload.party).toBe('party02');
  expect(plaintextsPayload.content).toContain('mock-plaintexts');
  const plaintextsDownloadRes = await page.request.get('http://127.0.0.1:4173/api/plaintexts/download?auxsid=default');
  expect(plaintextsDownloadRes.ok()).toBeTruthy();
  expect(plaintextsDownloadRes.headers()['content-disposition']).toContain('plaintexts');
  expect(await plaintextsDownloadRes.text()).toContain('mock-plaintexts');

  await party1.locator('#auxsidInput').fill('default');
  await party2.locator('#auxsidInput').fill('default');
  await party3.locator('#auxsidInput').fill('default');
  await runSequentialOperation([party1, party2, party3], 'Mezclar', 'shuffle', page);
  await expect(page.locator('#eventLog')).toContainText('AuxSID autoajustado a default2');
  await expect(page.locator('#eventLog')).toContainText('Reutilizando ciphertexts desde AuxSID default');
  await expect(party1.locator('#partyCurrentOperation')).toContainText('shuffle');
  await expect(party1.locator('#auxsidInput')).toHaveValue('default2');

  await party1.locator('#auxsidInput').fill('default');
  await party2.locator('#auxsidInput').fill('default');
  await party3.locator('#auxsidInput').fill('default');
  await runSequentialOperation([party1, party2, party3], 'Verificar', 'verify', page);

  await expect(page.locator('#eventLog')).toContainText('Etapa preliminar completada');
  await expect(page.locator('#eventLog')).toContainText('Fase verify completada en todas las parties.');
  await expectPartyConsole(party1, 'vmnv -v -e -mix protInfo.xml default');
  await expectPartyConsole(party2, 'verification ok');
  await expectPartyConsole(party3, 'verification ok');
  await expect(party1.locator('[data-phase="verify"]')).toContainText('completada');

  const auxsidsRes = await page.request.get('http://127.0.0.1:4170/api/auxsids');
  expect(auxsidsRes.ok()).toBeTruthy();
  const auxsidsPayload = await auxsidsRes.json();
  expect(auxsidsPayload.used_auxsids).toContain('default');
  expect(auxsidsPayload.reserved_auxsids).not.toContain('ext');
  expect(auxsidsPayload.reserved_auxsids).not.toContain('orig');

  const uploadRes2 = await page.request.post('http://127.0.0.1:4170/api/ciphertexts', {
    data: {
      ciphertexts: 'mock-uploaded-ciphertexts-2\n',
      auxsid: 'default',
      format: 'native',
      session_id: publicKeyPayload.session_id,
    },
  });
  expect(uploadRes2.ok()).toBeTruthy();
  const uploadPayload2 = await uploadRes2.json();
  expect(uploadPayload2.resolved_auxsid).toBe('default3');
  expect(uploadPayload2.auxsid_changed).toBeTruthy();
  expect(uploadPayload2.accumulated).toBeTruthy();
  expect(uploadPayload2.accumulated_from_auxsid).toBe('default2');
  expect(uploadPayload2.replicated_files[uploadPayload2.slots.ciphertexts_ext].length).toBe(3);
  await expect(page.locator('#eventLog')).toContainText('AuxSID autoajustado a default3');
  await expect(page.locator('#eventLog')).toContainText('Carga acumulada sobre ciphertexts previos desde AuxSID default2');
  await expect(party1.locator('#auxsidStatus')).toContainText('default3');
  const emissionContextRes2 = await page.request.get('http://127.0.0.1:4170/api/emission-context?auxsid=default');
  expect(emissionContextRes2.ok()).toBeTruthy();
  const emissionContextPayload2 = await emissionContextRes2.json();
  expect(emissionContextPayload2.session_name).toBe(publicKeyPayload.session_name);
  expect(emissionContextPayload2.auxsid).toBe('default3');
  expect(emissionContextPayload2.accumulated).toBeTruthy();
  expect(emissionContextPayload2.accumulated_from_auxsid).toBe('default3');
  const auxsidsRes2 = await page.request.get('http://127.0.0.1:4170/api/auxsids');
  expect(auxsidsRes2.ok()).toBeTruthy();
  const auxsidsPayload2 = await auxsidsRes2.json();
  expect(auxsidsPayload2.suggested_auxsid).toBe(emissionContextPayload2.auxsid);

  await page.locator('#label').fill('Servidor 2 QA');
  await page.locator('#election_name').fill('Elección Nueva QA');
  await page.locator('#sid').fill('ONPE-QA-2');
  await page.getByRole('button', { name: 'Nueva sesión y ejecutar keygen' }).click();
  await expectOperationOk(page, 'keygen');
  await expect(page.locator('#heroStatus')).toContainText('Servidor 2 QA');
  const publicKeyRes2 = await page.request.get('http://127.0.0.1:4170/api/public-key');
  expect(publicKeyRes2.ok()).toBeTruthy();
  const publicKeyPayload2 = await publicKeyRes2.json();
  expect(publicKeyPayload2.session_id).not.toBe(publicKeyPayload.session_id);
  expect(publicKeyPayload2.session_name).toBe('Servidor 2 QA');
  const handshakesRes2 = await page.request.get('http://127.0.0.1:4170/api/handshakes');
  expect(handshakesRes2.ok()).toBeTruthy();
  const handshakesPayload2 = await handshakesRes2.json();
  expect(handshakesPayload2.stations).toHaveLength(0);
  const uploadResNewSession = await page.request.post('http://127.0.0.1:4170/api/ciphertexts', {
    data: {
      ciphertexts: 'mock-uploaded-ciphertexts-new-session\n',
      auxsid: 'default',
      format: 'native',
      session_id: publicKeyPayload2.session_id,
      session_name: publicKeyPayload2.session_name,
    },
  });
  expect(uploadResNewSession.ok()).toBeTruthy();
  const uploadPayloadNewSession = await uploadResNewSession.json();
  expect(uploadPayloadNewSession.resolved_auxsid).toBe('default');
  expect(uploadPayloadNewSession.auxsid_changed).toBeFalsy();
  expect(uploadPayloadNewSession.accumulated).toBeFalsy();
  expect(uploadPayloadNewSession.accumulated_from_auxsid).toBe(null);
  await expect(page.locator('#eventLog')).toContainText('Se reinició el registro de estaciones para la nueva sesión');
});
