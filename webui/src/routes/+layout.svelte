<script lang="ts">
	import '../app.css';
	import { goto } from '$app/navigation';
	import { page } from '$app/state';
	import Button from '#lib/components/Button.svelte';
	import Mark from '#lib/components/Mark.svelte';
	import { live } from '#lib/live.svelte.ts';
	import { session } from '#lib/session.svelte.ts';
	import { theme } from '#lib/theme.svelte.ts';
	import Backup from '~icons/material-symbols/settings-backup-restore';
	import Dns from '~icons/material-symbols/dns';
	import DnsOutline from '~icons/material-symbols/dns-outline';
	import LiveTv from '~icons/material-symbols/live-tv';
	import LiveTvOutline from '~icons/material-symbols/live-tv-outline';
	import Movie from '~icons/material-symbols/movie';
	import MovieOutline from '~icons/material-symbols/movie-outline';
	import Palette from '~icons/material-symbols/palette';
	import PaletteOutline from '~icons/material-symbols/palette-outline';
	import Wifi from '~icons/material-symbols/wifi';
	import { onMount } from 'svelte';

	let { children } = $props();

	/** The areas, the open one with a filled icon and a bright label. */
	const tabs = [
		{ href: '/servers', label: 'Servers', icon: DnsOutline, open: Dns },
		{ href: '/channels', label: 'Live TV', icon: LiveTvOutline, open: LiveTv },
		{ href: '/ondemand', label: 'On Demand', icon: MovieOutline, open: Movie },
		{ href: '/themes', label: 'Themes', icon: PaletteOutline, open: Palette },
		{ href: '/web', label: 'Web control', icon: Wifi, open: Wifi },
		{ href: '/backup', label: 'Backup', icon: Backup, open: Backup }
	];

	const onPairPage = $derived(page.url.pathname.startsWith('/pair'));

	onMount(() => {
		// A closed socket may mean this browser was unpaired; the session says.
		live.onClosed = () => void session.refresh();
		void session.refresh();
		const profiles = live.subscribe(['profile'], () => void session.refresh());
		// The paired profile's theme, edited or picked on the TV or here, shows at once.
		const themes = live.subscribe(['theme', 'profile'], () => void theme.refresh());
		return () => {
			profiles();
			themes();
			live.stop();
		};
	});

	// Unpaired browsers go to the pairing page in the default theme; paired ones wear their profile's and get live updates.
	$effect(() => {
		if (session.status === 'unpaired') {
			live.stop();
			theme.reset();
			if (!onPairPage) void goto('/pair');
		}
		if (session.status === 'paired') {
			live.start();
			void theme.refresh();
		}
	});
</script>

{#if onPairPage}
	{@render children()}
{:else}
	<div class="min-h-screen">
		<!-- A website's top bar: the mark, then the areas in a row (scrolling sideways when narrow), the open one underlined. -->
			<header class="sticky top-0 z-10 border-b border-line bg-surface">
				<nav class="mx-auto flex h-14 w-full max-w-6xl items-stretch gap-2 px-2 min-[840px]:gap-4 min-[840px]:px-4" aria-label="Main">
					<a
						href="/"
						class="my-2 grid shrink-0 place-items-center rounded-control px-2 hover:bg-accent focus-visible:bg-accent"
						aria-label="Home"
						aria-current={page.url.pathname === '/' ? 'page' : undefined}
					>
						<Mark size={28} />
					</a>
					<div class="flex min-w-0 flex-1 items-stretch gap-1 overflow-x-auto [scrollbar-width:none]">
						{#each tabs as tab (tab.href)}
							{@const open = page.url.pathname.startsWith(tab.href)}
							{@const Icon = open ? tab.open : tab.icon}
							<a
								href={tab.href}
								aria-current={open ? 'page' : undefined}
								class={[
									'group/tab relative my-2 flex shrink-0 items-center gap-2 rounded-control px-3 text-label-l whitespace-nowrap hover:bg-accent hover:text-on-accent focus-visible:bg-accent focus-visible:text-on-accent',
									open ? 'font-semibold text-text' : 'font-medium text-text-muted'
								]}
							>
								<Icon class="size-5 transition-transform group-active/tab:scale-[0.88]" />
								<span class="max-[420px]:hidden">{tab.label}</span>
								{#if open}<span class="absolute inset-x-3 -bottom-2 h-0.5 bg-accent group-hover/tab:hidden group-focus-visible/tab:hidden" aria-hidden="true"></span>{/if}
							</a>
						{/each}
					</div>
				</nav>
			</header>
			<main>
			{#if session.status === 'paired'}
				{@render children()}
			{:else if session.status === 'offline'}
				<div class="mx-auto flex max-w-120 flex-col gap-4 px-4 py-12 min-[840px]:px-6">
					<Mark size={48} />
					<h1 class="font-display text-headline-m font-semibold">Can’t reach the TV</h1>
					<p class="text-text-muted">Make sure AnstelleTV is on and Web control is turned on in its Settings.</p>
					<div><Button label="Try again" primary onclick={() => session.refresh()} /></div>
				</div>
			{/if}
		</main>
	</div>
{/if}

{#if theme.spec?.effects.scanlines}
	<!-- The theme's picture-tube lines over everything, as on the TV. -->
	<div
		class="pointer-events-none fixed inset-0 z-50 bg-[repeating-linear-gradient(to_bottom,transparent_0_2px,#000_2px_3px)]"
		style:opacity="var(--theme-scanlines)"
		aria-hidden="true"
	></div>
{/if}
