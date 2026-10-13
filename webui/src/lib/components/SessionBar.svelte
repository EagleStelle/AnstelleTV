<script lang="ts">
	import { goto } from '$app/navigation';
	import Button from '#lib/components/Button.svelte';
	import Dialog from '#lib/components/Dialog.svelte';
	import { session } from '#lib/session.svelte.ts';
	import Logout from '~icons/material-symbols/logout';

	/**
	 * Who this browser is paired as: the device's name over the account's, then the one verb, unpair,
	 * in the danger red. An icon button in the top bar; [labeled] spells it out, anchored to the bottom
	 * of the screen on phones.
	 */
	let { labeled = false }: { labeled?: boolean } = $props();

	let asking = $state(false);

	async function unpair() {
		asking = false;
		await session.logout();
		await goto('/pair');
	}
</script>

{#if session.session}
	<div class="flex min-w-0 items-center gap-3">
		<p class={['flex min-w-0 flex-1 flex-col leading-tight', !labeled && 'text-right']}>
			<span class="truncate text-label-l font-medium text-text">{session.session.clientName}</span>
			<span class="truncate text-body-s text-text-muted">{session.session.profile.name}</span>
		</p>
		<button
			type="button"
			onclick={() => (asking = true)}
			aria-label="Unpair"
			title="Unpair"
			class={[
				'inline-flex shrink-0 items-center justify-center gap-2 rounded-control bg-danger text-on-danger transition-colors hover:bg-on-danger hover:text-danger focus-visible:bg-on-danger focus-visible:text-danger',
				labeled ? 'h-10 px-4 text-label-l font-semibold tracking-label' : 'size-9'
			]}
		>
			<Logout class="size-5" />{#if labeled}<span>Unpair</span>{/if}
		</button>
	</div>
{/if}

<Dialog bind:open={asking} title="Unpair this browser?" about="It will lose access to the TV until you pair it again with a new PIN.">
	{#snippet actions()}
		<Button label="Cancel" autofocus onclick={() => (asking = false)} />
		<Button label="Unpair" danger onclick={unpair} />
	{/snippet}
</Dialog>
