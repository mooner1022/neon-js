// The bot of the end-to-end test (DiscordKt), against the mock Discord the test runs: DISCORD_API points REST there,
// the gateway's address comes from REST, and discord.js reads the token from DISCORD_TOKEN itself.
const t0 = Date.now();
const { ActivityType, Client, Events, GatewayIntentBits } = require('discord.js');
const process = require('node:process');
const t1 = Date.now();

const client = new Client({
  intents: [GatewayIntentBits.Guilds, GatewayIntentBits.GuildMessages, GatewayIntentBits.MessageContent],
  rest: { api: process.env.DISCORD_API },
});
globalThis.timings = { require: t1 - t0, client: Date.now() - t1 };
if (process.env.DISCORD_DEBUG) client.on(Events.Debug, (m) => console.log(m));
client.on(Events.Error, (e) => console.error(e));

client.once(Events.ClientReady, (c) => {
  // what tells the mock that the bot is ready: a presence update over the gateway
  c.user.setPresence({ activities: [{ name: `${c.guilds.cache.size} guild`, type: ActivityType.Watching }], status: 'online' });
});

client.on(Events.MessageCreate, async (message) => {
  if (message.author.bot) return;
  if (message.content === '!ping') {
    await message.reply(`pong from ${client.user.tag} in #${message.channel.name} of ${message.guild.name}`);
  } else if (message.content === '!quit') {
    await client.destroy();
  }
});

client.login();
