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
    source: 'https://commons.wikimedia.org/wiki/File:Marina_Bay_Sands_and_illuminated_polyhedral_building_Louis_Vuitton_over_the_water_at_blue_hour_with_pink_clouds_in_Singapore.jpg',
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
};

export function photoFor(city: string): CityPhoto | undefined {
  return CITY_PHOTOS[city];
}
