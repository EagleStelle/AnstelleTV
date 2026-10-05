<script lang="ts">
	import { page } from '$app/state';
	import ComingSoon from '#lib/components/ComingSoon.svelte';
	import { configurePages } from '#lib/nav.ts';

	const current = $derived(configurePages.find((item) => item.slug === page.params.section));
</script>

{#if current}
	<ComingSoon page={current} />
{:else}
	<section class="card">
		<h2>Not found</h2>
		<p class="muted">There is no settings page called “{page.params.section}”.</p>
	</section>
{/if}
