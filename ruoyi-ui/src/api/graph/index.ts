import request from '@/utils/request';
import { AxiosPromise } from 'axios';
import { GraphResult, GraphStats } from './types';

/**
 * 查询直系亲属
 * @param gmsfhm 身份证号
 * @param level 扩展级数（0 默认，1..8）
 */
export const getDirectRelatives = (gmsfhm: string, level: number): AxiosPromise<GraphResult> => {
  return request({
    url: '/graph/person/direct',
    method: 'get',
    params: { gmsfhm, level }
  });
};

/**
 * 查询两人关系路径
 * @param from 身份证号 A
 * @param to 身份证号 B
 * @param level 最大级数（0 默认，1..8）
 */
export const getRelationPath = (from: string, to: string, level: number): AxiosPromise<GraphResult> => {
  return request({
    url: '/graph/relation/path',
    method: 'get',
    params: { from, to, level }
  });
};

/**
 * 图谱统计（首页数据总览）
 */
export const getGraphStats = (): AxiosPromise<GraphStats> => {
  return request({
    url: '/graph/stats',
    method: 'get'
  });
};
