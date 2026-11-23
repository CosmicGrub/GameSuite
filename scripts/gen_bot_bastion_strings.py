# Writes the Bot Bastion string files (EN + ES). Run from the repo root: python -I scripts/gen_bot_bastion_strings.py
import re, io

main = {
 'title': ('Bot Bastion', 'Bot Bastión'),
 'help_title': ('How to play Bot Bastion', 'Cómo jugar a Bot Bastión'),
 'help_text': ('Rock-ships called Drifters fly along the glowing lanes toward your Core. Tap an empty node to build a bot there. Pickets shoot all round, Lobbers lob big splash shots from far away but cannot hit what is beside them, Hazes have huge reach and low power, and Tenders slow and freeze ships. Skiffs patrol the edge of the arena. Tap a bot to upgrade it, change its target or sell it. Aegis adds a shield to the Core. Waves get bigger and tougher, and every tenth wave has a boss. Pause any time: you can still build while paused. If you play with friends, choose who is acting with the player chips and drag on the arena to steer their pilot ship.',
              'Naves rocosas llamadas Vagantes vuelan por los carriles brillantes hacia tu Núcleo. Toca un nodo vacío para construir un bot. Los Piquetes disparan en todas direcciones, los Morteros lanzan tiros de área desde lejos pero no alcanzan lo que tienen al lado, las Brumas tienen gran alcance y poca fuerza, y los Cuidadores frenan y congelan naves. Los Esquifes patrullan el borde de la arena. Toca un bot para mejorarlo, cambiar su objetivo o venderlo. Égida añade un escudo al Núcleo. Las oleadas crecen y se endurecen, y cada décima oleada trae un jefe. Puedes pausar en cualquier momento y seguir construyendo. Si juegas con amigos, elige quién actúa con las fichas de jugador y arrastra sobre la arena para guiar su nave piloto.'),
 'choose_map': ('Choose a map', 'Elige un mapa'),
 'map_hearth': ('Hearth Orbit', 'Órbita del Hogar'),
 'map_twin': ('Twin Gates', 'Puertas Gemelas'),
 'map_pebble': ('Pebble Ring', 'Anillo de Guijarros'),
 'map_cross': ('Crosswind', 'Viento Cruzado'),
 'map_wake': ('Long Wake', 'Estela Larga'),
 'map_rift': ('Rift Split', 'Grieta Dividida'),
 'map_pinwheel': ('Pinwheel', 'Molinillo'),
 'map_hourglass': ('Hourglass', 'Reloj de Arena'),
 'map_tidal': ('Tidal Lock', 'Bloqueo de Marea'),
 'map_lantern': ('Lantern Maze', 'Laberinto de Faroles'),
 'map_last': ('The Last Lantern', 'El Último Farol'),
 'sector_1': ('Cradle Reach', 'Alcance de la Cuna'),
 'sector_2': ('Glass Belt', 'Cinturón de Cristal'),
 'sector_3': ('Ember Deep', 'Abismo de Brasas'),
 'sector_4': ('Hush Verge', 'Margen del Silencio'),
 'map_info': ('%1$d lanes, %2$d nodes', '%1$d carriles, %2$d nodos'),
 'map_stars': ('%1$d of 3 stars', '%1$d de 3 estrellas'),
 'map_locked': ('Locked: needs %1$d stars', 'Bloqueado: necesita %1$d estrellas'),
 'total_stars': ('Stars: %1$d', 'Estrellas: %1$d'),
 'best': ('Best: wave %1$d, score %2$d', 'Mejor: oleada %1$d, puntos %2$d'),
 'no_maps': ('The maps could not be loaded.', 'No se pudieron cargar los mapas.'),
 'tier_label': ('Skill level', 'Nivel de dificultad'),
 'tier_cadet': ('Cadet', 'Cadete'),
 'tier_captain': ('Captain', 'Capitán'),
 'tier_hero': ('Galactic Hero', 'Héroe Galáctico'),
 'tier_insanity': ('Insanity', 'Locura'),
 'tier_endless': ('Endless', 'Sin fin'),
 'crew_label': ('Crew size (extra players fly pilot ships)', 'Tamaño de la tripulación (los demás pilotan naves)'),
 'boards': ('Top scores on this map and level', 'Mejores puntuaciones de este mapa y nivel'),
 'no_scores': ('No scores yet', 'Aún no hay puntuaciones'),
 'board_line': ('%1$d. %2$s: %3$d points, wave %4$d', '%1$d. %2$s: %3$d puntos, oleada %4$d'),
 'start': ('Start', 'Empezar'),
 'wave': ('Wave %1$d of %2$d', 'Oleada %1$d de %2$d'),
 'wave_endless': ('Wave %1$d', 'Oleada %1$d'),
 'scrap': ('Scrap %1$d', 'Chatarra %1$d'),
 'scrap_mult': ('Scrap x%1$d', 'Chatarra x%1$d'),
 'score': ('Score %1$d', 'Puntos %1$d'),
 'core': ('Core %1$d', 'Núcleo %1$d'),
 'shield': ('Shield %1$d', 'Escudo %1$d'),
 'pause': ('Pause', 'Pausa'),
 'resume': ('Resume', 'Seguir'),
 'speed': ('Speed %1$dx', 'Velocidad %1$dx'),
 'start_wave': ('Start wave', 'Empezar oleada'),
 'call_early': ('Call early (+%1$d Scrap)', 'Llamar ya (+%1$d de chatarra)'),
 'next_in': ('Next wave in %1$d s', 'Siguiente oleada en %1$d s'),
 'next_card': ('Next: wave %1$d, %2$d ships', 'Siguiente: oleada %1$d, %2$d naves'),
 'boss_warning': ('Boss wave!', '¡Oleada de jefe!'),
 'swarm_warning': ('Swarm after this wave', 'Enjambre tras esta oleada'),
 'paused_banner': ('Paused. You can still build.', 'En pausa. Aún puedes construir.'),
 'player_chip': ('Player %1$d: %2$d', 'Jugador %1$d: %2$d'),
 'bot_picket': ('Picket', 'Piquete'),
 'bot_lobber': ('Lobber', 'Mortero'),
 'bot_haze': ('Haze', 'Bruma'),
 'bot_skiff': ('Skiff', 'Esquife'),
 'bot_tender': ('Tender', 'Cuidador'),
 'desc_picket': ('All-round gun', 'Cañón en todas direcciones'),
 'desc_lobber': ('Big splash, long range, blind spot', 'Gran área, largo alcance, punto ciego'),
 'desc_haze': ('Huge reach, low power', 'Enorme alcance, poca fuerza'),
 'desc_skiff': ('Patrols the arena edge', 'Patrulla el borde de la arena'),
 'desc_tender': ('Slows and freezes ships', 'Frena y congela naves'),
 'tap_node': ('Tap a node to build, or a bot to upgrade it.', 'Toca un nodo para construir, o un bot para mejorarlo.'),
 'build_here': ('Build here', 'Construir aquí'),
 'buy_desc': ('costs %1$d Scrap', 'cuesta %1$d de chatarra'),
 'core_section': ('Core and Skiffs', 'Núcleo y Esquifes'),
 'aegis': ('Aegis shield +%1$d (%2$d Scrap)', 'Escudo Égida +%1$d (%2$d de chatarra)'),
 'buy_skiff': ('Buy Skiff (%1$d Scrap)', 'Comprar Esquife (%1$d de chatarra)'),
 'skiff_chip': ('Skiff %1$d, level %2$d', 'Esquife %1$d, nivel %2$d'),
 'level': ('Level %1$d', 'Nivel %1$d'),
 'max_level': ('Maximum level', 'Nivel máximo'),
 'upgrade': ('Upgrade to level %1$d (%2$d Scrap)', 'Mejorar al nivel %1$d (%2$d de chatarra)'),
 'sell': ('Sell (+%1$d Scrap)', 'Vender (+%1$d de chatarra)'),
 'stat_power': ('Power %1$s to %2$s', 'Fuerza %1$s a %2$s'),
 'stat_rate': ('Shots per second %1$s to %2$s', 'Disparos por segundo %1$s a %2$s'),
 'stat_range': ('Range %1$s to %2$s', 'Alcance %1$s a %2$s'),
 'stat_slow': ('Slow %1$s to %2$s', 'Freno %1$s a %2$s'),
 'stat_freeze': ('Freezes every %1$s s to %2$s s', 'Congela cada %1$s s a %2$s s'),
 'prio_label': ('Target', 'Objetivo'),
 'prio_first': ('First', 'Primero'),
 'prio_last': ('Last', 'Último'),
 'prio_weakest': ('Weakest', 'Más débil'),
 'prio_strongest': ('Strongest', 'Más fuerte'),
 'prio_closest': ('Closest', 'Más cercano'),
 'specials': ('Specials', 'Especiales'),
 'special_surge': ('Scrap Surge', 'Oleada de chatarra'),
 'special_boost': ('Score Boost', 'Impulso de puntos'),
 'special_nova': ('Nova Pulse', 'Pulso Nova'),
 'special_repair': ('Repair Kit', 'Kit de reparación'),
 'special_count': ('%1$s x%2$d', '%1$s x%2$d'),
 'why_scrap': ('Not enough Scrap.', 'No hay chatarra suficiente.'),
 'why_cap': ('You cannot use that right now.', 'No puedes usar eso ahora.'),
 'why_taken': ('Something is already built there.', 'Ya hay algo construido ahí.'),
 'victory': ('Victory!', '¡Victoria!'),
 'defeat': ('The Core fell', 'El Núcleo cayó'),
 'stars_earned': ('%1$d of 3 stars', '%1$d de 3 estrellas'),
 'result_wave': ('Reached wave %1$d', 'Llegaste a la oleada %1$d'),
 'result_core': ('Core left: %1$d', 'Núcleo restante: %1$d'),
 'initials_label': ('Your initials', 'Tus iniciales'),
 'save_score': ('Save score', 'Guardar puntuación'),
 'saved': ('Score saved', 'Puntuación guardada'),
 'retry': ('Play again', 'Jugar otra vez'),
 'next_map': ('Next map', 'Siguiente mapa'),
 'back_to_maps': ('Back to maps', 'Volver a los mapas'),
 'leave': ('Leave', 'Salir'),
 'tip_drifter': ('Add a Picket near the entry.', 'Añade un Piquete cerca de la entrada.'),
 'tip_darter': ('Try a Skiff or a Haze for fast ones.', 'Prueba un Esquife o una Bruma contra los rápidos.'),
 'tip_plater': ('Armoured. Use a Lobber or a Skiff.', 'Con armadura. Usa un Mortero o un Esquife.'),
 'tip_splitter': ('Splash from a Lobber clears the Bits.', 'El área de un Mortero limpia los Fragmentos.'),
 'tip_courier': ('It escaped. Put a Tender at the exit.', 'Escapó. Pon un Cuidador en la salida.'),
 'tip_behemoth': ('Save Nova Pulse and stack Lobbers.', 'Guarda el Pulso Nova y reúne Morteros.'),
 'arena': ('Arena', 'Arena'),
 'a11y_summary': ('Wave %1$d, Core %2$d, Scrap %3$d', 'Oleada %1$d, Núcleo %2$d, chatarra %3$d'),
 'announce_wave': ('Wave %1$d started', 'Empezó la oleada %1$d'),
 'announce_core_hit': ('The Core was hit. Core %1$d', 'El Núcleo fue golpeado. Núcleo %1$d'),
 'node_empty': ('Node %1$d of %2$d, empty', 'Nodo %1$d de %2$d, vacío'),
 'node_bot': ('Node %1$d of %2$d, %3$s level %4$d', 'Nodo %1$d de %2$d, %3$s nivel %4$d'),
}

hub = {
 'Bot Bastion': 'Bot Bastión',
 'Build bots and defend the Core.': 'Construye bots y defiende el Núcleo.',
 'Defend the Core. Friends can fly pilot ships.': 'Defiende el Núcleo. Los amigos pueden pilotar naves.',
 'Skill level': 'Nivel de dificultad',
 'Cadet is gentle; Endless never stops.': 'Cadete es suave; Sin fin no se detiene.',
 'Cadet': 'Cadete',
 'Softer ships and extra Scrap.': 'Naves más blandas y chatarra extra.',
 'Captain': 'Capitán',
 'The standard challenge.': 'El desafío normal.',
 'Galactic Hero': 'Héroe Galáctico',
 'Tough waves and swarm attacks.': 'Oleadas duras y ataques en enjambre.',
 'Insanity': 'Locura',
 'For experts: fast, strong and pricey.': 'Para expertos: rápido, fuerte y caro.',
 'Endless': 'Sin fin',
 'Hold on as long as you can.': 'Resiste todo lo que puedas.',
 'Crew size': 'Tamaño de la tripulación',
 'People sharing this device.': 'Personas que comparten este dispositivo.',
}

coach = {
 'coach_bot_bastion_s1': ('Rock-ships called Drifters fly along the glowing lanes toward your Core. If enough of them get through, the Core falls.',
                          'Naves rocosas llamadas Vagantes vuelan por los carriles brillantes hacia tu Núcleo. Si pasan suficientes, el Núcleo cae.'),
 'coach_bot_bastion_s2': ('Tap an empty node to build a bot. Picket shoots all round, Lobber hits groups from far away, Haze reaches a long way, Tender slows and freezes, and Skiff patrols the edge.',
                          'Toca un nodo vacío para construir un bot. El Piquete dispara en todas direcciones, el Mortero golpea grupos desde lejos, la Bruma llega muy lejos, el Cuidador frena y congela, y el Esquife patrulla el borde.'),
 'coach_bot_bastion_s3': ('Tap a bot to upgrade it up to level five, change who it aims at, or sell it for most of your Scrap back.',
                          'Toca un bot para mejorarlo hasta el nivel cinco, cambiar a quién apunta o venderlo recuperando casi toda la chatarra.'),
 'coach_bot_bastion_s4': ('Aegis adds a shield to the Core. Specials drop every few waves and from Couriers: Nova Pulse clears the screen and Repair Kit heals the Core.',
                          'Égida añade un escudo al Núcleo. Los especiales caen cada pocas oleadas y de los Mensajeros: el Pulso Nova limpia la pantalla y el Kit de reparación cura el Núcleo.'),
 'coach_bot_bastion_s5': ('Pause whenever you like and keep building. Every tenth wave brings a boss, so save a Special for it. Up to four people can play on one device.',
                          'Pausa cuando quieras y sigue construyendo. Cada décima oleada trae un jefe, así que guarda un especial. Hasta cuatro personas pueden jugar en un dispositivo.'),
 'coach_bot_bastion_fact': ('A space station that drifts in orbit stays up only because it keeps falling around the planet, never quite reaching it.',
                            'Una estación espacial en órbita se mantiene arriba porque cae sin parar alrededor del planeta sin llegar a tocarlo.'),
 'tip_bot_bastion_1': ('Put a Tender near the entry and your guns near the Core.', 'Pon un Cuidador cerca de la entrada y tus cañones cerca del Núcleo.'),
 'tip_bot_bastion_2': ('Lobbers clear crowds but miss ships right beside them.', 'Los Morteros limpian multitudes pero fallan a las naves pegadas a ellos.'),
 'tip_bot_bastion_3': ('Keep a Nova Pulse for the boss wave.', 'Guarda un Pulso Nova para la oleada del jefe.'),
}


def esc(s):
    assert "'" not in s, s
    return s.replace('&', '&amp;').replace('<', '&lt;').replace('>', '&gt;').replace('"', '\\"')


def hubkey(t):
    slug = re.sub(r'[^a-z0-9]+', '_', t.lower()).strip('_')
    assert 0 < len(slug) <= 48, t
    return 'hub_' + slug


def write(path, header, items):
    with io.open(path, 'w', encoding='utf-8', newline='\n') as f:
        f.write('<?xml version="1.0" encoding="utf-8"?>\n<resources>\n    <!-- %s -->\n' % header)
        for k, v in items:
            f.write('    <string name="%s">%s</string>\n' % (k, esc(v)))
        f.write('</resources>\n')


base = 'app/src/main/res/'
for idx, loc in ((0, 'values'), (1, 'values-es')):
    write(base + loc + '/bot_bastion_strings.xml', 'Bot Bastion screen texts', [('bot_bastion_' + k, v[idx]) for k, v in main.items()])
    write(base + loc + '/bot_bastion_coach_strings.xml', 'Coach card and tips of Bot Bastion', [(k, v[idx]) for k, v in coach.items()])
    items = []
    for en, es in hub.items():
        items.append((hubkey(en), en if idx == 0 else es))
    write(base + loc + '/bot_bastion_hub_strings.xml', 'Game Hub texts of Bot Bastion (hubKey names); shared texts such as Play already exist elsewhere', items)
print(len(main), len(hub), len(coach))
