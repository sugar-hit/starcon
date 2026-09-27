/**
 * 城市地标照片清单。
 *
 * 当前源:Wikimedia Commons 缩略图直连(公共领域 / CC 授权,作者见 author 字段)。
 * 直连 upload|thumb.wikimedia.org 而不是 Special:FilePath —— 后者多跳重定向
 * 会被 Chrome 的 ORB 判成跨源可疑响应直接拦掉(net::ERR_BLOCKED_BY_ORB);
 * 直连没有这个问题,且该域带 Access-Control-Allow-Origin: *,canvas 读像素可用。
 *
 * 切 CDN:只改 url 字段,组件与样式不动。
 */

/** 图片归因信息,渲染在设置页或文档里 */
export interface CityPhoto {
  /** 城市中文名,与 world-clock 的 city 字段对应 */
  city: string;
  /** 地标名称 */
  landmark: string;
  /** Commons 文件页,归因与许可证原始出处 */
  source: string;
  /** 照片 URL */
  url: string;
  license: string;
  author: string;
}

export const CITY_PHOTOS: Record<string, CityPhoto> = {
  北京: {
    city: '北京',
    landmark: '天安门夜景',
    source: 'https://commons.wikimedia.org/wiki/File:天安门夜景.jpg',
    url: 'https://thumb.wikimedia.org/wikipedia/commons/thumb/e/e4/%E5%A4%A9%E5%AE%89%E9%97%A8%E5%A4%9C%E6%99%AF.jpg/960px-%E5%A4%A9%E5%AE%89%E9%97%A8%E5%A4%9C%E6%99%AF.jpg',
    license: 'Public domain',
    author: 'Charlie fong 冯成',
  },
  纽约: {
    city: '纽约',
    landmark: '自由女神像',
    source: 'https://commons.wikimedia.org/wiki/File:Statue_of_Liberty,_NY.jpg',
    url: 'https://thumb.wikimedia.org/wikipedia/commons/thumb/d/d3/Statue_of_Liberty%2C_NY.jpg/960px-Statue_of_Liberty%2C_NY.jpg',
    license: 'CC BY 2.0',
    author: 'William Warby',
  },
  伦敦: {
    city: '伦敦',
    landmark: '大本钟日落',
    source: 'https://commons.wikimedia.org/wiki/File:Big_Ben_at_sunset_-_2014-10-27_17-30.jpg',
    url: 'https://thumb.wikimedia.org/wikipedia/commons/thumb/9/97/Big_Ben_at_sunset_-_2014-10-27_17-30.jpg/960px-Big_Ben_at_sunset_-_2014-10-27_17-30.jpg',
    license: 'CC BY-SA 4.0',
    author: 'Colin',
  },
  新加坡: {
    city: '新加坡',
    landmark: '滨海湾金沙',
    source:
      'https://commons.wikimedia.org/wiki/File:Marina_Bay_Sands_and_illuminated_polyhedral_building_Louis_Vuitton_over_the_water_at_blue_hour_with_pink_clouds_in_Singapore.jpg',
    url: 'https://thumb.wikimedia.org/wikipedia/commons/thumb/8/8c/Marina_Bay_Sands_and_illuminated_polyhedral_building_Louis_Vuitton_over_the_water_at_blue_hour_with_pink_clouds_in_Singapore.jpg/960px-Marina_Bay_Sands_and_illuminated_polyhedral_building_Louis_Vuitton_over_the_water_at_blue_hour_with_pink_clouds_in_Singapore.jpg',
    license: 'CC BY-SA 4.0',
    author: 'Basile Morin',
  },
  东京: {
    city: '东京',
    landmark: '东京塔',
    source: 'https://commons.wikimedia.org/wiki/File:Tokyo_Tower,_Minato_City.jpg',
    url: 'https://thumb.wikimedia.org/wikipedia/commons/thumb/6/6d/Tokyo_Tower%2C_Minato_City.jpg/960px-Tokyo_Tower%2C_Minato_City.jpg',
    license: 'CC BY 4.0',
    author: 'David Kernan',
  },
  悉尼: {
    city: '悉尼',
    landmark: '悉尼歌剧院',
    source: 'https://commons.wikimedia.org/wiki/File:Sydney_Opera_House_-_Dec_2008.jpg',
    url: 'https://thumb.wikimedia.org/wikipedia/commons/thumb/7/7c/Sydney_Opera_House_-_Dec_2008.jpg/960px-Sydney_Opera_House_-_Dec_2008.jpg',
    license: 'CC BY-SA 3.0',
    author: 'Diliff',
  },

  洛杉矶: {
    city: '洛杉矶',
    landmark: 'Hollywood Sign',
    source: 'https://commons.wikimedia.org/wiki/File:Hollywood_Sign.jpg',
    url: 'https://thumb.wikimedia.org/wikipedia/commons/thumb/0/00/Hollywood_Sign.jpg/960px-Hollywood_Sign.jpg',
    author: 'Thomas Wolf, www.foto-tw.de',
    license: 'CC BY-SA 3.0',
  },
  芝加哥: {
    city: '芝加哥',
    landmark: 'Willis Tower',
    source: 'https://commons.wikimedia.org/wiki/File:Willis_Tower.jpg',
    url: 'https://thumb.wikimedia.org/wikipedia/commons/thumb/9/9b/Willis_Tower.jpg/960px-Willis_Tower.jpg',
    author: 'Daniel Schwen',
    license: 'CC BY-SA 2.5',
  },
  多伦多: {
    city: '多伦多',
    landmark: 'CN Tower Toronto',
    source: 'https://commons.wikimedia.org/wiki/File:Toronto_-_ON_-_Toronto_Harbourfront7.jpg',
    url: 'https://thumb.wikimedia.org/wikipedia/commons/thumb/9/96/Toronto_-_ON_-_Toronto_Harbourfront7.jpg/960px-Toronto_-_ON_-_Toronto_Harbourfront7.jpg',
    author: 'Wladyslaw',
    license: 'CC BY-SA 3.0',
  },
  温哥华: {
    city: '温哥华',
    landmark: 'Vancouver skyline',
    source: 'https://commons.wikimedia.org/wiki/File:Vancouver_dusk_pano.jpg',
    url: 'https://thumb.wikimedia.org/wikipedia/commons/thumb/2/27/Vancouver_dusk_pano.jpg/960px-Vancouver_dusk_pano.jpg',
    author: 'Mfield, Matthew Field, http://www.photography.mattfield.com',
    license: 'CC BY-SA 3.0',
  },
  墨西哥城: {
    city: '墨西哥城',
    landmark: 'Palacio de Bellas Artes',
    source: 'https://commons.wikimedia.org/wiki/File:Mexico_City_Palacio_de_bellas_artes.jpg',
    url: 'https://thumb.wikimedia.org/wikipedia/commons/thumb/8/85/Mexico_City_Palacio_de_bellas_artes.jpg/960px-Mexico_City_Palacio_de_bellas_artes.jpg',
    author: 'Jeses',
    license: 'CC BY-SA 2.5',
  },
  巴黎: {
    city: '巴黎',
    landmark: 'Eiffel Tower',
    source: 'https://commons.wikimedia.org/wiki/File:Tour_Eiffel_Wikimedia_Commons.jpg',
    url: 'https://thumb.wikimedia.org/wikipedia/commons/thumb/a/a8/Tour_Eiffel_Wikimedia_Commons.jpg/960px-Tour_Eiffel_Wikimedia_Commons.jpg',
    author: 'Benh LIEU SONG',
    license: 'Public domain',
  },
  法兰克福: {
    city: '法兰克福',
    landmark: 'Frankfurt skyline',
    source: 'https://commons.wikimedia.org/wiki/File:Skyline_Frankfurt_am_Main_2015.jpg',
    url: 'https://thumb.wikimedia.org/wikipedia/commons/thumb/d/d7/Skyline_Frankfurt_am_Main_2015.jpg/960px-Skyline_Frankfurt_am_Main_2015.jpg',
    author: 'Christian Wolf (www.c-w-design.de)',
    license: 'CC BY-SA 3.0 de',
  },
  阿姆斯特丹: {
    city: '阿姆斯特丹',
    landmark: 'Amsterdam canal',
    source:
      'https://commons.wikimedia.org/wiki/File:Water_reflection_of_canal_houses_at_blue_hour_in_Damrak_Amsterdam_the_Netherlands.jpg',
    url: 'https://thumb.wikimedia.org/wikipedia/commons/thumb/d/d3/Water_reflection_of_canal_houses_at_blue_hour_in_Damrak_Amsterdam_the_Netherlands.jpg/960px-Water_reflection_of_canal_houses_at_blue_hour_in_Damrak_Amsterdam_the_Netherlands.jpg',
    author: 'Basile Morin',
    license: 'CC BY-SA 4.0',
  },
  马德里: {
    city: '马德里',
    landmark: 'Royal Palace Madrid',
    source: 'https://commons.wikimedia.org/wiki/File:Royal_Palace_of_Madrid_east_facade_1.jpg',
    url: 'https://thumb.wikimedia.org/wikipedia/commons/thumb/d/da/Royal_Palace_of_Madrid_east_facade_1.jpg/960px-Royal_Palace_of_Madrid_east_facade_1.jpg',
    author: 'kallerna',
    license: 'CC BY-SA 4.0',
  },
  斯德哥尔摩: {
    city: '斯德哥尔摩',
    landmark: 'Stockholm City Hall',
    source: 'https://commons.wikimedia.org/wiki/File:Stockholms_stadshus_February_2026_02.jpg',
    url: 'https://thumb.wikimedia.org/wikipedia/commons/thumb/0/06/Stockholms_stadshus_February_2026_02.jpg/960px-Stockholms_stadshus_February_2026_02.jpg',
    author: 'ArildV',
    license: 'CC BY-SA 4.0',
  },
  曼谷: {
    city: '曼谷',
    landmark: 'Wat Arun',
    source:
      'https://commons.wikimedia.org/wiki/File:Templo_Wat_Arun,_Bangkok,_Tailandia,_2013-08-22,_DD_30.jpg',
    url: 'https://thumb.wikimedia.org/wikipedia/commons/thumb/b/b3/Templo_Wat_Arun%2C_Bangkok%2C_Tailandia%2C_2013-08-22%2C_DD_30.jpg/960px-Templo_Wat_Arun%2C_Bangkok%2C_Tailandia%2C_2013-08-22%2C_DD_30.jpg',
    author: 'Diego Delso',
    license: 'CC BY-SA 3.0',
  },
  吉隆坡: {
    city: '吉隆坡',
    landmark: 'Petronas Twin Towers',
    source:
      'https://commons.wikimedia.org/wiki/File:Petronas_Towers_at_Night_-_from_the_base_upwards.jpg',
    url: 'https://thumb.wikimedia.org/wikipedia/commons/thumb/4/44/Petronas_Towers_at_Night_-_from_the_base_upwards.jpg/960px-Petronas_Towers_at_Night_-_from_the_base_upwards.jpg',
    author: 'Dcubillas',
    license: 'CC BY-SA 3.0',
  },
  雅加达: {
    city: '雅加达',
    landmark: 'Monas',
    source: 'https://commons.wikimedia.org/wiki/File:Jakarta_Panorama.jpg',
    url: 'https://thumb.wikimedia.org/wikipedia/commons/thumb/f/f3/Jakarta_Panorama.jpg/960px-Jakarta_Panorama.jpg',
    author: 'Gunawan Kartapranata',
    license: 'CC BY-SA 3.0',
  },
  马尼拉: {
    city: '马尼拉',
    landmark: 'Manila Cathedral',
    source:
      'https://commons.wikimedia.org/wiki/File:Cathedral_Basilica_of_the_Immaculate_Conception_(23775939900).jpg',
    url: 'https://thumb.wikimedia.org/wikipedia/commons/thumb/3/30/Cathedral_Basilica_of_the_Immaculate_Conception_%2823775939900%29.jpg/960px-Cathedral_Basilica_of_the_Immaculate_Conception_%2823775939900%29.jpg',
    author: 'Paul Sableman',
    license: 'CC BY 2.0',
  },
  胡志明市: {
    city: '胡志明市',
    landmark: 'Notre Dame Cathedral Saigon',
    source: 'https://commons.wikimedia.org/wiki/File:Basilica_of_Our_Lady_Immaculate.jpg',
    url: 'https://thumb.wikimedia.org/wikipedia/commons/thumb/a/a0/Basilica_of_Our_Lady_Immaculate.jpg/960px-Basilica_of_Our_Lady_Immaculate.jpg',
    author: 'Maksim Sokolov (maxergon.com)',
    license: 'CC BY-SA 4.0',
  },
  首尔: {
    city: '首尔',
    landmark: 'Gyeongbokgung',
    source:
      'https://commons.wikimedia.org/wiki/File:Gyeonghoeru_(Royal_Banquet_Hall)_at_Gyeongbokgung_Palace,_Seoul.jpg',
    url: 'https://thumb.wikimedia.org/wikipedia/commons/thumb/5/5d/Gyeonghoeru_%28Royal_Banquet_Hall%29_at_Gyeongbokgung_Palace%2C_Seoul.jpg/960px-Gyeonghoeru_%28Royal_Banquet_Hall%29_at_Gyeongbokgung_Palace%2C_Seoul.jpg',
    author: 'Frank Schulenburg',
    license: 'CC BY-SA 4.0',
  },
  孟买: {
    city: '孟买',
    landmark: 'Gateway of India',
    source:
      'https://commons.wikimedia.org/wiki/File:Panoramic_view_of_Taj_Palace_Hotel_and_Taj_Tower_with_the_iconic_Gateway_of_India_in_the_background.jpg',
    url: 'https://thumb.wikimedia.org/wikipedia/commons/thumb/d/df/Panoramic_view_of_Taj_Palace_Hotel_and_Taj_Tower_with_the_iconic_Gateway_of_India_in_the_background.jpg/960px-Panoramic_view_of_Taj_Palace_Hotel_and_Taj_Tower_with_the_iconic_Gateway_of_India_in_the_background.jpg',
    author: 'iMahesh',
    license: 'CC BY-SA 4.0',
  },
  迪拜: {
    city: '迪拜',
    landmark: 'Burj Khalifa',
    source: 'https://commons.wikimedia.org/wiki/File:Burj_Khalifa_(16260269606).jpg',
    url: 'https://thumb.wikimedia.org/wikipedia/commons/thumb/a/a6/Burj_Khalifa_%2816260269606%29.jpg/960px-Burj_Khalifa_%2816260269606%29.jpg',
    author: 'Laika ac',
    license: 'CC BY-SA 2.0',
  },
  阿拉木图: {
    city: '阿拉木图',
    landmark: 'Almaty Tower',
    source: 'https://commons.wikimedia.org/wiki/File:TV-Turm_Almaty_-_3.jpg',
    url: 'https://thumb.wikimedia.org/wikipedia/commons/thumb/7/72/TV-Turm_Almaty_-_3.jpg/960px-TV-Turm_Almaty_-_3.jpg',
    author: 'Michael Grau',
    license: 'CC BY-SA 3.0',
  },
  德黑兰: {
    city: '德黑兰',
    landmark: 'Azadi Tower',
    source: 'https://commons.wikimedia.org/wiki/File:Azadi_Tower_at_night_in_low_angle.jpg',
    url: 'https://thumb.wikimedia.org/wikipedia/commons/thumb/4/42/Azadi_Tower_at_night_in_low_angle.jpg/960px-Azadi_Tower_at_night_in_low_angle.jpg',
    author: 'بهادر های زاده',
    license: 'CC BY-SA 4.0',
  },
  圣保罗: {
    city: '圣保罗',
    landmark: 'Catedral da Sé São Paulo',
    source:
      'https://commons.wikimedia.org/wiki/File:Catedral_da_S%C3%A9,_S%C3%A3o_Paulo,_Brasil.jpg',
    url: 'https://thumb.wikimedia.org/wikipedia/commons/thumb/e/ef/Catedral_da_S%C3%A9%2C_S%C3%A3o_Paulo%2C_Brasil.jpg/960px-Catedral_da_S%C3%A9%2C_S%C3%A3o_Paulo%2C_Brasil.jpg',
    author: 'Wilfredor',
    license: 'CC0',
  },
  布宜诺斯艾利斯: {
    city: '布宜诺斯艾利斯',
    landmark: 'Obelisco Buenos Aires',
    source:
      'https://commons.wikimedia.org/wiki/File:Parte_superior_del_Obelisco_de_Buenos_Aires.jpg',
    url: 'https://thumb.wikimedia.org/wikipedia/commons/thumb/b/ba/Parte_superior_del_Obelisco_de_Buenos_Aires.jpg/960px-Parte_superior_del_Obelisco_de_Buenos_Aires.jpg',
    author: 'ProtoplasmaKid',
    license: 'CC BY-SA 4.0',
  },
  圣地亚哥: {
    city: '圣地亚哥',
    landmark: 'Santiago Chile skyline',
    source:
      'https://commons.wikimedia.org/wiki/File:Gran_Santiago_Tower,_Views_from_San_Crit%C3%B3bal_Hill_-_Santiago_-_Chile_08.jpg',
    url: 'https://thumb.wikimedia.org/wikipedia/commons/thumb/1/1b/Gran_Santiago_Tower%2C_Views_from_San_Crit%C3%B3bal_Hill_-_Santiago_-_Chile_08.jpg/960px-Gran_Santiago_Tower%2C_Views_from_San_Crit%C3%B3bal_Hill_-_Santiago_-_Chile_08.jpg',
    author: '3BRBS',
    license: 'CC BY-SA 3.0',
  },
  墨尔本: {
    city: '墨尔本',
    landmark: 'Melbourne skyline',
    source:
      'https://commons.wikimedia.org/wiki/File:Melbourne_Skyline_from_Rialto_Crop_-_Nov_2008.jpg',
    url: 'https://thumb.wikimedia.org/wikipedia/commons/thumb/1/1e/Melbourne_Skyline_from_Rialto_Crop_-_Nov_2008.jpg/960px-Melbourne_Skyline_from_Rialto_Crop_-_Nov_2008.jpg',
    author: 'Diliff',
    license: 'CC BY-SA 3.0',
  },
  奥克兰: {
    city: '奥克兰',
    landmark: 'Sky Tower Auckland',
    source: 'https://commons.wikimedia.org/wiki/File:Sky_Tower_Auckland_01.jpg',
    url: 'https://thumb.wikimedia.org/wikipedia/commons/thumb/3/3d/Sky_Tower_Auckland_01.jpg/960px-Sky_Tower_Auckland_01.jpg',
    author: 'Krzysztof Golik',
    license: 'CC BY-SA 4.0',
  },
  开普敦: {
    city: '开普敦',
    landmark: 'Table Mountain',
    source:
      'https://commons.wikimedia.org/wiki/File:Cape_Town_(ZA),_Table_Mountain_--_2024_--_2825.jpg',
    url: 'https://thumb.wikimedia.org/wikipedia/commons/thumb/4/43/Cape_Town_%28ZA%29%2C_Table_Mountain_--_2024_--_2825.jpg/960px-Cape_Town_%28ZA%29%2C_Table_Mountain_--_2024_--_2825.jpg',
    author: 'Dietmar Rabich',
    license: 'CC BY-SA 4.0',
  },
  拉各斯: {
    city: '拉各斯',
    landmark: 'Lagos skyline',
    source: 'https://commons.wikimedia.org/wiki/File:Lagos_skyline_with_marina.jpg',
    url: 'https://thumb.wikimedia.org/wikipedia/commons/thumb/f/f6/Lagos_skyline_with_marina.jpg/960px-Lagos_skyline_with_marina.jpg',
    author: 'kopee15',
    license: 'Public domain',
  },
  内罗毕: {
    city: '内罗毕',
    landmark: 'Nairobi skyline',
    source:
      'https://commons.wikimedia.org/wiki/File:Buffalo_Nairobi_Skyline_Savannah_Kenya_May19_R1600769.jpg',
    url: 'https://thumb.wikimedia.org/wikipedia/commons/thumb/d/d2/Buffalo_Nairobi_Skyline_Savannah_Kenya_May19_R1600769.jpg/960px-Buffalo_Nairobi_Skyline_Savannah_Kenya_May19_R1600769.jpg',
    author:
      'This Photo was taken by Timothy A. Gonsalves.  Feel free to use my photos, but please mention me as the author.  I would much appreciate if you send me an email tagooty@yahoo.com or write on my talk page, for my information.  Please contact me before commercial use.\n\n\n\nPlease do not upload an edited image here without consulting me.  I would like to make corrections only at my own source to ensure that the changes improve the image and are preserved.Otherwise you may upload an edited image with a new name. Please use one of the templates derivative or extract.',
    license: 'CC BY-SA 4.0',
  },
};

export function photoFor(city: string): CityPhoto | undefined {
  return CITY_PHOTOS[city];
}
