<script lang="ts">
	import type { Snippet } from 'svelte';
	import ChevronRight from '~icons/material-symbols/chevron-right';
	import Info from '~icons/material-symbols/info-outline';

	/**
	 * A page of the web control, laid out as a website rather than the TV's guided panel. A header
	 * (breadcrumb, title with the page's verbs at its end, the note) closes on a hairline; under it
	 * the page's readout as a band, then the list flat on the ground, its rows bleeding into the
	 * gutter so their icons line up with the title. Settings pages keep to a reading column; a
	 * builder ([aside]: a lineup's reach, a theme's preview) sets the result beside the list from
	 * 1024 px, sticky while the list scrolls, and above it when narrower. [wide] gives the whole
	 * width to the list (the home's overview). Explanations live only in the note.
	 */
	interface Props {
		title: string;
		trail?: { label: string; href: string }[];
		note?: string;
		/** The page's verbs, at the title's end: what you came to do here. */
		actions?: Snippet;
		/** What the page is about at a glance (a readout, what airs), between the header and the list. */
		overview?: Snippet;
		/** The result a builder shows as it is built, beside the list. */
		aside?: Snippet;
		wide?: boolean;
		children: Snippet;
	}

	let { title, trail = [], note, actions, overview, aside, wide = false, children }: Props = $props();
</script>

<div class="mx-auto w-full max-w-6xl px-4 pb-10 min-[840px]:px-6 min-[840px]:pb-14">
	<div class={[!aside && !wide && 'max-w-3xl']}>
		<header class="flex flex-col gap-2 border-b border-line pt-5 pb-4 min-[840px]:pt-8 min-[840px]:pb-5">
			{#if trail.length > 0}
				<nav class="flex flex-wrap items-center gap-1 text-body-m text-text-muted" aria-label="Breadcrumb">
					{#each trail as crumb, i (crumb.href)}
						{#if i > 0}<ChevronRight class="size-4" aria-hidden="true" />{/if}
						<a
							href={crumb.href}
							class="-mx-1 rounded-control px-1 hover:bg-accent hover:text-on-accent focus-visible:bg-accent focus-visible:text-on-accent">{crumb.label}</a
						>
					{/each}
				</nav>
			{/if}
			<div class="flex flex-col gap-4 min-[600px]:flex-row min-[600px]:items-end min-[600px]:justify-between min-[600px]:gap-8">
				<h1 class="min-w-0 font-display text-headline-s font-semibold whitespace-pre-wrap tabular-nums wrap-anywhere min-[840px]:text-headline-l">{title}</h1>
				{#if actions}
					<div class="flex shrink-0 flex-wrap gap-2 max-[599px]:*:flex-1 min-[600px]:pb-0.5">{@render actions()}</div>
				{/if}
			</div>
			{#if note}
				<p class="flex max-w-[68ch] gap-2.5 text-body-m text-text-muted">
					<Info class="mt-px size-4.5 shrink-0" aria-hidden="true" /><span>{note}</span>
				</p>
			{/if}
		</header>
		{#if overview}<div class="pt-4 min-[840px]:pt-6">{@render overview()}</div>{/if}
		<div class={['grid items-start pt-2 min-[840px]:pt-3', aside && 'gap-6 min-[1024px]:grid-cols-[minmax(0,1fr)_360px] min-[1024px]:gap-12']}>
			<section class="-mx-4 flex min-w-0 flex-col gap-0.5" aria-label={title}>
				{@render children()}
			</section>
			{#if aside}
				<div class="order-first min-w-0 pt-3 min-[1024px]:sticky min-[1024px]:top-20 min-[1024px]:order-last min-[1024px]:pt-4">
					{@render aside()}
				</div>
			{/if}
		</div>
	</div>
</div>
