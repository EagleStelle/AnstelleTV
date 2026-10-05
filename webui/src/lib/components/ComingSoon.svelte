<script lang="ts">
	import type { ConfigurePage } from '#lib/nav.ts';

	let { page }: { page: ConfigurePage } = $props();
	const Icon = $derived(page.icon);
</script>

<section class="card coming">
	<Icon class="icon big" />
	<div class="stack">
		<h2>{page.title}</h2>
		<p class="muted">{page.description}</p>
		<p class="muted small">Arrives in development phase {page.phase}.</p>
	</div>
</section>

<style>
	.coming {
		display: flex;
		gap: 1.25rem;
		align-items: flex-start;
	}

	.coming :global(.big) {
		width: 2.5rem;
		height: 2.5rem;
		color: var(--primary);
	}

	.small {
		font-size: 0.85rem;
	}
</style>
