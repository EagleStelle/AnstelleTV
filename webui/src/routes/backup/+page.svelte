<script lang="ts">
	import { goto } from '$app/navigation';
	import { api, problem, type Backup } from '#lib/api.ts';
	import BackupReadout from '#lib/components/BackupReadout.svelte';
	import Button from '#lib/components/Button.svelte';
	import Dialog from '#lib/components/Dialog.svelte';
	import Notice from '#lib/components/Notice.svelte';
	import Page from '#lib/components/Page.svelte';
	import { live } from '#lib/live.svelte.ts';
	import { session } from '#lib/session.svelte.ts';
	import Download from '~icons/material-symbols/download';
	import Upload from '~icons/material-symbols/upload';
	import { onMount } from 'svelte';

	let backup = $state<Backup | null>(null);
	/** A problem asks for an answer, so it opens over the page; only progress stays in the list. */
	let error = $state<string | null>(null);
	let file = $state<File | null>(null);
	let question = $state<{ question: string; explanation: string } | null>(null);
	let asking = $state(false);
	let restoring = $state(false);
	let picker = $state<HTMLInputElement>();

	async function load() {
		try {
			backup = await api.backup();
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
	<!-- The file verbs side by side: saving one, and restoring one for the owner. -->
	{#snippet actions()}
		{#if !restoring}
			<Button label="Save backup" icon={Download} primary href={api.backupUrl} download />
			{#if session.isOwner}
				<Button label="Restore from file" icon={Upload} danger onclick={() => picker?.click()} />
			{/if}
		{/if}
	{/snippet}
	{#snippet overview()}
		{#if backup}<BackupReadout {backup} />{/if}
	{/snippet}

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

<Dialog open={error !== null} title="That didn’t work" about={error ?? undefined} onclose={() => (error = null)}>
	{#snippet actions()}
		<Button label="Okay" primary autofocus onclick={() => (error = null)} />
	{/snippet}
</Dialog>
