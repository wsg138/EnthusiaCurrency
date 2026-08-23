# EnthusiaCurrency — SMP Player Guide

This file documents the current player-facing economy behavior on Enthusia SMP. The main [`README.md`](README.md) remains the technical/moderation API reference.

The values below were checked against the live production configuration and current source on August 22, 2026.

## Physical currency

Enthusia's economy is backed by ordinary Minecraft **Raw Gold** items.

Current production denominations:

| Physical item | Bank value |
| --- | ---: |
| 1 Raw Gold | 1 |
| 1 Raw Gold Block | 9 |

The plugin currently has both `use-name` and `use-lore` disabled, so normal unrenamed/unlored Raw Gold and Raw Gold Blocks count as currency. A special custom currency item is not required.

A player's wealth can exist in two places:

1. **bank balance** stored by the economy plugin; and
2. **physical Raw Gold / Raw Gold Blocks** carried in the player's inventory.

For an online player, `/balance` reports all three numbers: bank, physical items, and the combined total.

## Naming note

The broader Enthusia server and several other plugins describe this currency as **Raw Gold**. EnthusiaCurrency's current production display configuration still uses:

```text
Dollar / Dollars
$
```

for its command messages and Vault-facing display strings.

That is a naming/configuration inconsistency, not a different currency. The actual physical backing is Raw Gold, and this document refers to the economy as Raw Gold where practical.

## Balance

```text
/balance
/bal
/money
```

shows your own balance as:

- total wealth;
- banked currency;
- currency currently carried as Raw Gold/Raw Gold Blocks.

Viewing another player's balance requires the staff/operator `currency.balance.others` permission, so `/balance <player>` is not a normal public player lookup.

## Depositing physical Raw Gold

```text
/deposit
/deposit all
```

both deposit **all** currency items currently carried in the player's inventory.

You can instead deposit a specific value:

```text
/deposit <amount>
```

The requested amount has to be representable by the Raw Gold/Raw Gold Block denominations the player actually has. For example, the plugin will not silently break an unusable block denomination into arbitrary change during a partial deposit when the requested combination cannot be represented safely.

Depositing removes the corresponding physical Raw Gold/blocks and increases the persistent bank balance by the same value.

The current economy is whole-number only; decimal amounts are disabled.

## Withdrawing physical currency

```text
/withdraw <amount>
```

converts bank balance back into physical Raw Gold / Raw Gold Blocks.

The plugin chooses a block/item denomination combination and checks the inventory **before** taking money from the bank. If the resulting physical stacks will not fit, the withdrawal is rejected rather than deducting the balance and dropping unexpected currency.

A withdrawal cannot exceed the banked balance; physical currency already in the inventory is not needed because the command specifically converts bank funds into item form.

## Paying another player

```text
/pay <player> <amount>
```

can pay players who are online or offline.

Important behavior:

- you cannot pay yourself;
- the amount must be a positive whole number;
- the payment can use your **combined wealth**, not just your bank balance;
- the plugin spends banked funds first and can then consume physical currency from your inventory for the remainder;
- any denomination overage created while consuming physical currency is credited back to your bank rather than lost;
- the recipient receives the payment in their **bank balance**;
- an offline recipient is informed about the payment when they next join.

This means carrying physical Raw Gold does not prevent `/pay` from working merely because the same value has not been deposited first.

## Balance leaderboard

```text
/baltop [page]
```

opens the production balance leaderboard GUI. The current GUI is enabled and refreshes its underlying ranking about every **15 seconds** when needed.

For online players, leaderboard calculations can include their current physical item balance as well as bank balance. Offline players can only contribute the persistent bank amount until their carried items can be observed again, so the system maintains item-balance tracking/caches to keep the public board as current as practical.

The current production leaderboard is also exported for use by the Enthusia website.

## Raw Gold blocks are real currency

Because a Raw Gold Block is worth exactly nine units, crafting Raw Gold into blocks does not remove it from the economy. Both forms are recognized by the plugin.

Similarly, the physical currency is not just a visual representation of bank money: Raw Gold can be carried, lost, stolen, traded through normal Minecraft mechanics, deposited, withdrawn, or spent through integrated Vault systems.

## Other server systems

EnthusiaCurrency is the Vault economy provider used by other server features. Systems such as rewards, market features, events, reputation stalking, and other plugins can debit or credit the same economy according to their own rules.

When a supported Vault withdrawal is made against an **online** player, the currency provider can use that player's combined bank + physical currency rather than treating carried Raw Gold as invisible wealth. For an offline player, only the persistent bank balance is available to the economy provider because the player's live inventory is not loaded.

## PlaceholderAPI

The expansion identifier is `currency`.

Useful placeholders include:

```text
%currency_balance%   # combined bank + physical balance
%currency_bank%      # bank only
%currency_items%     # physical currency currently tracked
%currency_top3%      # true/false for current top-three membership
```

Leaderboard placeholders use:

```text
%currency_top_<board>_<rank>_<field>%
```

with the exact supported board/field values resolved by the plugin's leaderboard cache. Production exposes ranks up to the configured maximum of 100.

## Current production summary

| Setting | Enthusia SMP |
| --- | --- |
| Physical item | Raw Gold |
| Block form | Raw Gold Block |
| Block value | 9 |
| Custom-name requirement | No |
| Custom-lore requirement | No |
| Decimal balances | No |
| Starting bank balance | 0 |
| `/baltop` GUI | Enabled |
| Balance-board refresh | 15 seconds |
| Public website leaderboard export | Enabled |
| PlaceholderAPI | Enabled |
| Plan analytics | Enabled |

## What belongs on the public wiki

Useful player-facing wiki information includes:

- Raw Gold and Raw Gold Block denomination values;
- the difference between banked and physical currency;
- `/balance`, `/deposit`, `/withdraw`, `/pay`, and `/baltop`;
- the fact that `/pay` can use carried physical Raw Gold after bank funds;
- offline payments and their next-login notification;
- the fact that physical Raw Gold remains normal lootable/tradable Minecraft items;
- any server-wide public naming decision (Raw Gold versus the plugin's legacy Dollar/$ strings).

The moderation removal API, SQLite revisions, persistence leases, analytics internals, R2 credentials/export plumbing, and staff-only balance inspection do not belong on the normal player wiki.
