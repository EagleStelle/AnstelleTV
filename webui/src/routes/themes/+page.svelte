<script lang="ts">
	import { goto } from '$app/navigation';
	import { api, problem, type Theme } from '#lib/api.ts';
	import Button from '#lib/components/Button.svelte';
	import Dialog from '#lib/components/Dialog.svelte';
	import MenuKey from '#lib/components/MenuKey.svelte';
	import Notice from '#lib/components/Notice.svelte';
	import Page from '#lib/components/Page.svelte';
	import Row from '#lib/components/Row.svelte';
	import Search from '#lib/components/Search.svelte';
	import Section from '#lib/components/Section.svelte';
	import Swatches from '#lib/components/Swatches.svelte';
	import ThemePreview from '#lib/components/ThemePreview.svelte';
	import WithKey from '#lib/components/WithKey.svelte';
	import { section } from '#lib/format.ts';
	import { live } from '#lib/live.svelte.ts';
	import { session } from '#lib/session.svelte.ts';
	import { theme as worn } from '#lib/theme.svelte.ts';
	import Add from '~icons/material-symbols/add';
	import ContentCopy from '~icons/material-symbols/content-copy';
	import Delete from '~icons/material-symbols/delete';
	import Download from '~icons/material-symbols/download';
	import Edit from '~icons/material-symbols/edit';
	import Upload from '~icons/material-symbols/upload';
	import { onMount } from 'svelte';

	let themes = $state<Theme[] | null>(null);
	/** The theme the pointer or the keyboard is on; the guidance previews it, else the one worn. */
	let pointed = $state<number | null>(null);
	let query = $state('');
	let error = $state<string | null>(null);
	let message = $state<string | null>(null);
	let deleting = $state<Theme | null>(null);
	let confirmOpen = $state(false);
	let picker = $state<HTMLInputElement>();

	async function load() {
		try {
			themes = await api.themes();
			error = null;
		} catch (e) {
			error = problem(e);
		}
	}

	onMount(() => {
		void load();
		return live.subscribe(['theme', 'profile'], () => void load());
	});

	async function act(action: () => Promise<unknown>) {
		try {
			message = null;
			await action();
			error = null;
		} catch (e) {
			error = problem(e);
		}
	}

	/** Puts [theme] on the paired profile: this browser and the TV wear it at once. */
	function use(theme: Theme) {
		if (theme.inUse) return;
		void act(async () => {
			await worn.use(theme.id);
			await load();
		});
	}

	async function importFile(event: Event) {
		const file = (event.currentTarget as HTMLInputElement).files?.[0];
		if (!file) return;
		await act(async () => {
			const theme = await api.importTheme(file);
			message = `Added ${theme.name}.`;
			await load();
		});
		if (picker) picker.value = '';
	}

	const needle = $derived(query.trim().toLowerCase());
	const shown = $derived((themes ?? []).filter((t) => t.name.toLowerCase().includes(needle)));
	const custom = $derived(shown.filter((t) => !t.isBuiltIn));
	const builtIn = $derived(shown.filter((t) => t.isBuiltIn));
	const previewed = $derived((themes ?? []).find((t) => t.id === pointed && t.spec) ?? (themes ?? []).find((t) => t.inUse));
</script>

{#snippet rows(list: Theme[])}
	{#each list as theme (theme.id)}
		<WithKey>
			<!-- A radio puts the theme on the paired profile, here and on the TV; its menu edits, copies, saves or deletes it. -->
			<Row
				title={theme.name}
				onclick={() => use(theme)}
				onmouseenter={() => (pointed = theme.id)}
				onfocus={() => (pointed = theme.id)}
				role="radio"
				aria-checked={theme.inUse}
			>
				{#snippet leading()}
					<span
						class={[
							'grid size-5 place-items-center rounded-full border-2 group-hover/row:border-on-accent group-focus-visible/row:border-on-accent',
							theme.inUse ? 'border-accent' : 'border-text-muted'
						]}
						aria-hidden="true"
					>
						{#if theme.inUse}<span class="size-2.5 rounded-full bg-accent group-hover/row:bg-on-accent group-focus-visible/row:bg-on-accent"></span>{/if}
					</span>
				{/snippet}
				{#snippet end()}<Swatches colors={theme.swatches} />{/snippet}
			</Row>
			{#snippet key()}
			<MenuKey
				title={theme.name}
				items={[
					...(theme.isBuiltIn ? [] : [{ label: 'Edit', icon: Edit, onclick: () => goto(`/themes/${theme.id}`) }]),
					{ label: 'Copy', icon: ContentCopy, onclick: () => act(async () => goto(`/themes/${(await api.copyTheme(theme.id)).id}`)) },
					{ label: 'Save file', icon: Download, href: api.themeFileUrl(theme.id), download: true },
					...(theme.isBuiltIn
						? []
						: [
								{
									label: 'Delete',
									icon: Delete,
									danger: true,
									onclick: () => {
										deleting = theme;
										confirmOpen = true;
									}
								}
							])
				]}
			/>
			{/snippet}
		</WithKey>
	{/each}
{/snippet}

<Page
	title="Themes"
	trail={[{ label: 'Home', href: '/' }]}
	note="Picking a theme sets it for {session.session?.profile.name ?? 'your profile'} on the TV and here. Built-in themes can be copied, not changed."
>
	{#snippet actions()}
		<Button label="New theme" icon={Add} onclick={() => act(async () => goto(`/themes/${(await api.newTheme()).id}`))} />
		<Button label="Import" icon={Upload} onclick={() => picker?.click()} />
	{/snippet}
	{#snippet aside()}
		{#if previewed?.spec}<ThemePreview spec={previewed.spec} />{/if}
	{/snippet}
	{#if error}<Notice text={error} error />{/if}
	{#if message}<Notice text={message} />{/if}
	<Search bind:value={query} label="Find a theme" />
	<input bind:this={picker} class="sr-only" type="file" accept="application/json,.json" onchange={importFile} tabindex="-1" />
	{#if custom.length > 0}
		<Section title={section('Custom themes', custom.length)} />
		{@render rows(custom)}
	{/if}
	{#if builtIn.length > 0}
		<Section title="Built in" />
		{@render rows(builtIn)}
	{/if}
	{#if themes && shown.length === 0}<Notice text="No theme has that name." />{/if}
</Page>

<Dialog bind:open={confirmOpen} title="Delete {deleting?.name}?" about="Profiles using it go back to the default.">
	{#snippet actions()}
		<Button label="Cancel" autofocus onclick={() => (confirmOpen = false)} />
		<Button
			label="Delete"
			danger
			onclick={() => {
				confirmOpen = false;
				const theme = deleting;
				if (theme) void act(async () => {
					await api.deleteTheme(theme.id);
					await load();
				});
			}}
		/>
	{/snippet}
</Dialog>
