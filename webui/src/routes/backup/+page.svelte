<script lang="ts">
	import { goto } from '$app/navigation';
	import { api, problem, type Backup } from '#lib/api.ts';
	import Button from '#lib/components/Button.svelte';
	import Dialog from '#lib/components/Dialog.svelte';
	import Facts from '#lib/components/Facts.svelte';
	import Figure from '#lib/components/Figure.svelte';
	import Figures from '#lib/components/Figures.svelte';
	import Notice from '#lib/components/Notice.svelte';
	import Page from '#lib/components/Page.svelte';
	import Readout from '#lib/components/Readout.svelte';
	import Rule from '#lib/components/Rule.svelte';
	import StatusLine from '#lib/components/StatusLine.svelte';
	import { ago, counted } from '#lib/format.ts';
	import { live } from '#lib/live.svelte.ts';
	import { session } from '#lib/session.svelte.ts';
	import Download from '~icons/material-symbols/download';
	import Restore from '~icons/material-symbols/settings-backup-restore';
	import { onMount } from 'svelte';

	let backup = $state<Backup | null>(null);
	let error = $state<string | null>(null);
	let file = $state<File | null>(null);
	let question = $state<{ question: string; explanation: string } | null>(null);
	let asking = $state(false);
	let restoring = $state(false);
	let picker = $state<HTMLInputElement>();

	async function load() {
		try {
			backup = await api.backup();
			error = null;
		} catch (e) {
			error = problem(e);
		}
	}

	onMount(() => {
		void load();
		return live.subscribe(['profile', 'channel', 'channel_group', 'theme', 'media_server', 'watch_event', 'setting'], () => void load());
	});

	/** Reads the file first: the question says what it brings before anything changes. */
	async function pick(event: Event) {
		const chosen = (event.currentTarget as HTMLInputElement).files?.[0];
		if (picker) picker.value = '';
		if (!chosen) return;
		try {
			question = await api.checkBackup(chosen);
			file = chosen;
			asking = true;
			error = null;
		} catch (e) {
			error = problem(e);
		}
	}

	async function restore() {
		asking = false;
		if (!file) return;
		try {
			restoring = true;
			await api.restore(file);
			// The TV starts over on the backup, and every pairing goes with the profiles it replaced.
			await waitForTv();
			await session.refresh();
			await goto('/pair');
		} catch (e) {
			restoring = false;
			error = problem(e);
		}
	}

	async function waitForTv() {
		await new Promise((resolve) => setTimeout(resolve, 3000));
		for (let i = 0; i < 60; i++) {
			try {
				await api.health();
				return;
			} catch {
				await new Promise((resolve) => setTimeout(resolve, 2000));
			}
		}
	}
</script>

<Page
	title="Backup"
	trail={[{ label: 'Home', href: '/' }]}
	note="A backup holds the channels, profiles, themes, settings and dashboard history, and restoring one replaces everything on the TV. Sign-ins never travel: servers the TV isn't signed in to need signing in again."
>
	{#snippet actions()}
		{#if !restoring}
			<Button label="Save backup" icon={Download} primary href={api.backupUrl} download />
			{#if session.isOwner}
				<Button label="Restore from file" icon={Restore} danger onclick={() => picker?.click()} />
			{/if}
		{/if}
	{/snippet}
	{#snippet overview()}
		{#if backup}
			<Readout>
				{#if backup.savedUtcMs}
					<StatusLine label="Saved" text={ago(backup.savedUtcMs)} />
				{:else}
					<StatusLine label="Not saved yet" state="off" />
				{/if}
				<Figures>
					<Figure value={backup.channels} name={backup.channels === 1 ? 'channel' : 'channels'} lead />
					<Figure value={backup.profiles} name={backup.profiles === 1 ? 'profile' : 'profiles'} />
					<Figure value={backup.themes} name={backup.themes === 1 ? 'custom theme' : 'custom themes'} />
				</Figures>
				<Rule />
				<Facts
					items={[
						['Servers', counted(backup.servers, 'server')],
						['Groups', counted(backup.groups, 'group')],
						['History', backup.historyDays === 1 ? '1 day' : `${backup.historyDays} days`]
					]}
				/>
			</Readout>
		{/if}
	{/snippet}

	{#if error}<Notice text={error} error />{/if}
	{#if restoring}
		<Notice text="Restoring… The TV starts over on the backup, then this browser pairs again." />
	{:else}
		<input bind:this={picker} class="sr-only" type="file" accept="application/json,.json" onchange={pick} tabindex="-1" />
	{/if}
</Page>

<Dialog bind:open={asking} title={question?.question ?? ''} about={question?.explanation}>
	{#snippet actions()}
		<Button label="Cancel" autofocus onclick={() => (asking = false)} />
		<Button label="Replace" danger onclick={restore} />
	{/snippet}
</Dialog>
