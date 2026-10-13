<script lang="ts">
	import type { Component } from 'svelte';
	import Button from './Button.svelte';
	import Dialog from './Dialog.svelte';

	/** A destructive verb, red, that asks first: what goes and what stays, then Cancel or the red answer. */
	interface Props {
		label: string;
		question: string;
		explanation: string;
		confirm: string;
		onconfirm: () => void | Promise<void>;
		icon?: Component<any>;
	}

	let { label, question, explanation, confirm, onconfirm, icon }: Props = $props();
	let open = $state(false);

	async function answer() {
		open = false;
		await onconfirm();
	}
</script>

<Button {label} {icon} danger onclick={() => (open = true)} />

<Dialog bind:open title={question} about={explanation}>
	{#snippet actions()}
		<Button label="Cancel" autofocus onclick={() => (open = false)} />
		<Button label={confirm} danger onclick={answer} />
	{/snippet}
</Dialog>
