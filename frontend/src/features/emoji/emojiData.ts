// The emoji the picker offers, in groups. Each entry is "<emoji> <name and search words>".
// A small hand-picked set (about 450) of the emoji people use most, so the app needs no emoji library and loads nothing
// from other websites (the Content-Security-Policy would not allow it anyway). To add one, add a line here.
// Country flags are left out on purpose: Windows has no flag pictures (it shows two letters such as "IN"), so they would
// look broken for every Windows user. Flags that are TYPED or pasted still work everywhere they are supported.

export interface EmojiItem {
  emoji: string
  name: string // also what the search looks at
}

export interface EmojiGroup {
  id: string
  label: string
  icon: string // the emoji shown on the tab
  items: EmojiItem[]
}

const RAW: { id: string; label: string; icon: string; items: string[] }[] = [
  {
    id: 'smileys',
    label: 'Smileys',
    icon: '😀',
    items: [
      '😀 grinning face happy', '😃 big smile happy', '😄 smile happy joy', '😁 beaming grin', '😆 laughing squint', '😅 sweat smile relief',
      '🤣 rolling on the floor laughing lol', '😂 tears of joy laugh lol', '🙂 slight smile', '🙃 upside down face', '😉 wink', '😊 blush smile',
      '😇 angel halo innocent', '🥰 smiling with hearts love', '😍 heart eyes love', '🤩 star struck wow', '😘 blowing kiss', '😗 kissing',
      '☺️ smiling relaxed', '😚 kissing closed eyes', '😙 kissing smiling eyes', '🥲 smile with tear touched', '😋 yummy delicious', '😛 tongue out',
      '😜 wink tongue silly', '🤪 zany crazy', '😝 squint tongue', '🤑 money mouth rich', '🤗 hug hugging', '🤭 hand over mouth oops giggle',
      '🤫 shush quiet secret', '🤔 thinking hmm', '🫡 salute', '🤐 zipper mouth secret', '🤨 raised eyebrow suspicious', '😐 neutral face',
      '😑 expressionless', '😶 no mouth silent', '🫥 dotted line face', '😏 smirk', '😒 unamused', '🙄 eye roll', '😬 grimace awkward',
      '🤥 lying pinocchio', '😌 relieved calm', '😔 pensive sad', '😪 sleepy', '🤤 drooling', '😴 sleeping zzz tired', '😷 mask sick',
      '🤒 thermometer sick fever', '🤕 head bandage hurt', '🤢 nauseated sick', '🤮 vomiting', '🤧 sneezing', '🥵 hot face', '🥶 cold face freezing',
      '🥴 woozy dizzy', '😵 dizzy knocked out', '🤯 mind blown exploding head', '🤠 cowboy hat', '🥳 party celebration birthday', '🥸 disguise glasses',
      '😎 cool sunglasses', '🤓 nerd glasses', '🧐 monocle curious', '😕 confused', '🫤 diagonal mouth meh', '😟 worried', '🙁 slightly frowning',
      '☹️ frowning', '😮 open mouth surprised', '😯 hushed', '😲 astonished shocked', '😳 flushed embarrassed', '🥺 pleading puppy eyes', '🥹 holding back tears',
      '😦 frowning open mouth', '😧 anguished', '😨 fearful scared', '😰 anxious sweat', '😥 sad relieved', '😢 crying tear sad', '😭 loudly crying sobbing',
      '😱 screaming fear', '😖 confounded', '😣 persevering', '😞 disappointed', '😓 downcast sweat', '😩 weary', '😫 tired', '🥱 yawning bored',
      '😤 steam from nose triumph', '😡 angry red face', '😠 angry', '🤬 cursing swearing', '😈 smiling devil', '👿 angry devil', '💀 skull dead',
      '☠️ skull crossbones', '💩 poop', '🤡 clown', '👻 ghost', '👽 alien', '🤖 robot', '😺 grinning cat', '😸 grinning cat smiling eyes',
      '😹 cat tears of joy', '😻 cat heart eyes', '🙈 see no evil monkey', '🙉 hear no evil monkey', '🙊 speak no evil monkey',
    ],
  },
  {
    id: 'people',
    label: 'Hands & people',
    icon: '👍',
    items: [
      '👍 thumbs up like yes ok', '👎 thumbs down dislike no', '👌 ok hand perfect', '🤌 pinched fingers italian', '🤏 pinching small', '✌️ victory peace',
      '🤞 crossed fingers luck', '🫰 hand with index finger and thumb crossed', '🤟 love you gesture', '🤘 rock on horns', '🤙 call me hand', '👈 point left',
      '👉 point right', '👆 point up', '🖕 middle finger', '👇 point down', '☝️ index pointing up', '🫵 pointing at you', '👋 wave hello hi bye',
      '🤚 raised back of hand', '🖐️ hand with fingers splayed', '✋ raised hand stop high five', '🖖 vulcan salute', '🫱 rightwards hand', '🫲 leftwards hand',
      '👏 clapping applause bravo', '🙌 raising hands celebration hooray', '🫶 heart hands', '👐 open hands', '🤲 palms up together', '🤝 handshake deal',
      '🙏 folded hands pray thanks please', '✍️ writing hand', '💅 nail polish', '🤳 selfie', '💪 flexed biceps strong muscle', '🦾 mechanical arm',
      '🦵 leg', '🦶 foot', '👂 ear', '👃 nose', '🧠 brain', '👀 eyes look', '👁️ eye', '👅 tongue', '👄 mouth lips', '👶 baby', '🧒 child', '👦 boy',
      '👧 girl', '🧑 person', '👨 man', '👩 woman', '🧓 older person', '👴 old man', '👵 old woman', '🙋 person raising hand', '🙇 bowing', '🤦 facepalm',
      '🤷 shrug', '💁 information desk', '🙅 no gesture', '🙆 ok gesture', '💃 dancer dancing', '🕺 man dancing', '🧘 meditation yoga', '🏃 running',
      '🚶 walking', '👨‍💻 man technologist computer developer', '👩‍💻 woman technologist computer developer', '👨‍👩‍👧 family', '👫 couple holding hands', '💏 kiss couple',
      '💑 couple with heart',
    ],
  },
  {
    id: 'hearts',
    label: 'Hearts & symbols',
    icon: '❤️',
    items: [
      '❤️ red heart love', '🧡 orange heart', '💛 yellow heart', '💚 green heart', '💙 blue heart', '💜 purple heart', '🖤 black heart', '🤍 white heart',
      '🤎 brown heart', '💔 broken heart', '❤️‍🔥 heart on fire', '❣️ heart exclamation', '💕 two hearts', '💞 revolving hearts', '💓 beating heart', '💗 growing heart',
      '💖 sparkling heart', '💘 heart with arrow cupid', '💝 heart with ribbon gift', '💟 heart decoration', '💯 hundred points perfect', '💢 anger symbol',
      '💥 collision boom', '💫 dizzy star', '💦 sweat droplets splash', '💨 dash wind', '💬 speech bubble chat', '💭 thought bubble', '💤 zzz sleep',
      '✨ sparkles shine magic', '⭐ star', '🌟 glowing star', '🔥 fire hot lit', '🎉 party popper tada celebration', '🎊 confetti ball', '🏆 trophy winner',
      '🥇 gold medal first', '🎯 bullseye target', '✅ check mark done', '❌ cross mark wrong', '❓ question mark', '❗ exclamation mark', '‼️ double exclamation',
      '⚠️ warning', '🚫 prohibited', '🔴 red circle', '🟢 green circle', '🔵 blue circle', '⚡ high voltage lightning', '♻️ recycling', '➕ plus', '➖ minus',
      '➡️ right arrow', '⬅️ left arrow', '⬆️ up arrow', '⬇️ down arrow', '🔄 counterclockwise arrows refresh', '🔁 repeat', '🔔 bell notification', '🔕 bell muted',
      '🔒 locked', '🔓 unlocked', '🔑 key',
    ],
  },
  {
    id: 'animals',
    label: 'Animals & nature',
    icon: '🐶',
    items: [
      '🐶 dog face puppy', '🐱 cat face kitten', '🐭 mouse', '🐹 hamster', '🐰 rabbit bunny', '🦊 fox', '🐻 bear', '🐼 panda', '🐨 koala', '🐯 tiger',
      '🦁 lion', '🐮 cow', '🐷 pig', '🐸 frog', '🐵 monkey face', '🐔 chicken', '🐧 penguin', '🐦 bird', '🐤 baby chick', '🦆 duck', '🦅 eagle',
      '🦉 owl', '🦇 bat', '🐺 wolf', '🐴 horse', '🦄 unicorn', '🐝 honeybee', '🦋 butterfly', '🐛 bug caterpillar', '🐌 snail', '🐞 ladybug', '🐜 ant',
      '🐢 turtle', '🐍 snake', '🐙 octopus', '🦀 crab', '🐠 tropical fish', '🐬 dolphin', '🐳 whale', '🦈 shark', '🐊 crocodile', '🐘 elephant',
      '🦒 giraffe', '🐪 camel', '🐑 sheep', '🐐 goat', '🐕 dog', '🐈 cat', '🐇 rabbit', '🦔 hedgehog', '🌵 cactus', '🌲 evergreen tree', '🌳 tree',
      '🌴 palm tree', '🌱 seedling', '🍀 four leaf clover luck', '🍁 maple leaf autumn', '🌸 cherry blossom', '🌹 rose', '🌻 sunflower', '🌼 blossom', '🌷 tulip',
      '☀️ sun sunny', '🌙 crescent moon night', '⭐ star', '☁️ cloud', '🌧️ rain cloud', '⛈️ thunderstorm', '❄️ snowflake', '🌈 rainbow', '🌊 water wave sea',
    ],
  },
  {
    id: 'food',
    label: 'Food & drink',
    icon: '🍕',
    items: [
      '🍏 green apple', '🍎 red apple', '🍐 pear', '🍊 orange tangerine', '🍋 lemon', '🍌 banana', '🍉 watermelon', '🍇 grapes', '🍓 strawberry', '🫐 blueberries',
      '🍒 cherries', '🍑 peach', '🥭 mango', '🍍 pineapple', '🥥 coconut', '🥝 kiwi', '🍅 tomato', '🥑 avocado', '🍆 eggplant', '🥕 carrot', '🌽 corn',
      '🌶️ hot pepper chili', '🥔 potato', '🍞 bread', '🥐 croissant', '🧀 cheese', '🥚 egg', '🍳 cooking fried egg', '🥞 pancakes', '🥓 bacon', '🍗 chicken leg',
      '🍖 meat on bone', '🌭 hot dog', '🍔 hamburger burger', '🍟 french fries', '🍕 pizza', '🥪 sandwich', '🌮 taco', '🌯 burrito', '🥗 green salad',
      '🍝 spaghetti pasta', '🍜 steaming bowl noodles ramen', '🍲 pot of food stew', '🍛 curry rice', '🍣 sushi', '🍱 bento box', '🍙 rice ball', '🍚 cooked rice',
      '🍦 soft ice cream', '🍨 ice cream', '🍩 doughnut donut', '🍪 cookie', '🎂 birthday cake', '🍰 shortcake cake', '🧁 cupcake', '🍫 chocolate bar', '🍬 candy',
      '🍭 lollipop', '🍿 popcorn', '☕ hot beverage coffee tea', '🍵 teacup green tea', '🧃 juice box', '🥤 cup with straw soda', '🍺 beer mug', '🍻 clinking beer mugs cheers',
      '🥂 clinking glasses cheers', '🍷 wine glass', '🍸 cocktail', '🍹 tropical drink', '🥛 glass of milk', '💧 droplet water',
    ],
  },
  {
    id: 'activities',
    label: 'Activities',
    icon: '⚽',
    items: [
      '⚽ soccer football', '🏀 basketball', '🏈 american football', '⚾ baseball', '🎾 tennis', '🏐 volleyball', '🏉 rugby', '🎱 pool 8 ball', '🏓 ping pong',
      '🏸 badminton', '🥊 boxing glove', '🥋 martial arts', '⛳ golf flag', '🏹 bow and arrow', '🎣 fishing', '🏊 swimming', '🚴 biking cycling', '🏋️ weight lifting',
      '🎮 video game controller', '🕹️ joystick', '🎲 game die dice', '🧩 puzzle piece', '♟️ chess pawn', '🎯 bullseye', '🎳 bowling', '🎰 slot machine',
      '🎨 artist palette art', '🎭 performing arts theatre', '🎬 clapper board movie', '🎤 microphone sing karaoke', '🎧 headphones music', '🎵 musical note', '🎶 musical notes',
      '🎹 piano keyboard', '🥁 drum', '🎸 guitar', '🎺 trumpet', '🎻 violin', '🎁 wrapped gift present', '🎈 balloon', '🎀 ribbon', '🎄 christmas tree',
      '🎃 jack o lantern halloween', '🎆 fireworks', '🎇 sparkler', '🧨 firecracker', '🪔 diya lamp diwali',
    ],
  },
  {
    id: 'travel',
    label: 'Travel & places',
    icon: '✈️',
    items: [
      '🚗 car automobile', '🚕 taxi', '🚌 bus', '🚎 trolleybus', '🏎️ racing car', '🚓 police car', '🚑 ambulance', '🚒 fire engine', '🚚 delivery truck', '🚜 tractor',
      '🛵 motor scooter', '🏍️ motorcycle', '🚲 bicycle', '🛴 kick scooter', '🚂 locomotive train', '🚆 train', '🚇 metro subway', '🚊 tram', '✈️ airplane flight',
      '🛫 airplane departure', '🛬 airplane arrival', '🚁 helicopter', '🚀 rocket launch', '🛸 flying saucer ufo', '⛵ sailboat', '🚤 speedboat', '🚢 ship',
      '⚓ anchor', '🗺️ world map', '🧭 compass', '🏠 house home', '🏡 house with garden', '🏢 office building', '🏥 hospital', '🏦 bank', '🏨 hotel', '🏫 school',
      '🏭 factory', '⛪ church', '🕌 mosque', '🛕 hindu temple', '🗼 tokyo tower', '🗽 statue of liberty', '🏖️ beach umbrella', '🏝️ desert island', '⛰️ mountain',
      '🌋 volcano', '🏕️ camping', '🌅 sunrise', '🌆 cityscape dusk', '🌃 night with stars', '🌍 globe europe africa', '🌎 globe americas', '🌏 globe asia',
    ],
  },
  {
    id: 'objects',
    label: 'Objects',
    icon: '💡',
    items: [
      '⌚ watch', '📱 mobile phone', '☎️ telephone', '📞 telephone receiver call', '💻 laptop computer', '🖥️ desktop computer', '⌨️ keyboard', '🖱️ computer mouse',
      '🖨️ printer', '📷 camera', '📸 camera flash', '📹 video camera', '🎥 movie camera', '📺 television', '📻 radio', '⏰ alarm clock', '⏳ hourglass', '🔋 battery',
      '🔌 electric plug', '💡 light bulb idea', '🔦 flashlight', '🕯️ candle', '💰 money bag', '💵 dollar banknote', '💳 credit card', '💎 gem diamond', '🧰 toolbox',
      '🔧 wrench', '🔨 hammer', '⚙️ gear settings', '🧲 magnet', '💊 pill medicine', '💉 syringe', '🩺 stethoscope', '🧪 test tube', '🔬 microscope', '🔭 telescope',
      '📡 satellite antenna', '🛒 shopping cart', '🎒 backpack', '👓 glasses', '🕶️ sunglasses', '👔 necktie', '👕 t shirt', '👖 jeans', '👗 dress', '👟 running shoe',
      '👑 crown', '💍 ring', '👜 handbag', '📦 package box', '📫 mailbox', '✉️ envelope email', '📧 e-mail', '📝 memo note', '📄 page document', '📊 bar chart',
      '📈 chart increasing', '📉 chart decreasing', '📅 calendar', '📌 pushpin', '📎 paperclip', '✂️ scissors', '🗑️ wastebasket trash', '📚 books', '📖 open book',
      '🔍 magnifying glass search', '🛡️ shield', '🔗 link', '🧯 fire extinguisher', '🪄 magic wand',
    ],
  },
]

export const EMOJI_GROUPS: EmojiGroup[] = RAW.map((group) => ({
  id: group.id,
  label: group.label,
  icon: group.icon,
  items: group.items.map((line) => {
    const space = line.indexOf(' ')
    return { emoji: line.slice(0, space), name: line.slice(space + 1) }
  }),
}))

// Every emoji once (a few appear in two groups), for the search.
export const ALL_EMOJI: EmojiItem[] = (() => {
  const seen = new Set<string>()
  const all: EmojiItem[] = []
  for (const group of EMOJI_GROUPS) {
    for (const item of group.items) {
      if (!seen.has(item.emoji)) {
        seen.add(item.emoji)
        all.push(item)
      }
    }
  }
  return all
})()

// Search: every word typed must appear in the name / search words ("heart eyes" finds 😍).
export function searchEmoji(query: string): EmojiItem[] {
  const words = query.toLowerCase().split(/\s+/).filter(Boolean)
  if (words.length === 0) return []
  return ALL_EMOJI.filter((item) => words.every((word) => item.name.toLowerCase().includes(word)))
}
