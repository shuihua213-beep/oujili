
// #ifdef H5
export const BASE_URL = `${location.protocol}//${location.host}/wxmapi`
// #endif
// #ifndef H5
// 请求接口
export const BASE_URL = 'https://www.wxmblog.com/wxmapi'; //正式接口  
//export const BASE_URL = 'http://192.168.31.121:8088'; //本地接口  
// #endif

// #ifdef MP-WEIXIN
export const AMAPKEY = '7528e756feaebfabd1abbb1b04097a1e'; //高德定位小程序
// #endif
// #ifdef APP-PLUS
export const AMAPKEY = 'cdd98f785c3d27a17bf4d7022783ede9'; //高德定位APP
// #endif

// 存储正在进行的请求
const pendingRequests = new Map();

// 生成请求唯一key的函数（考虑url、method、data，且data的key顺序不影响）
const generateRequestKey = (options) => {
	const url = BASE_URL + "/" + options.url;
	const method = options.method || "GET";
	const data = options.data || {};
	
	// 深度排序对象的key，确保相同内容不同顺序的对象生成相同的key
	const sortObject = (obj) => {
		if (obj === null || typeof obj !== 'object') {
			return obj;
		}
		if (Array.isArray(obj)) {
			return obj.map(sortObject);
		}
		const sortedKeys = Object.keys(obj).sort();
		const result = {};
		for (const key of sortedKeys) {
			result[key] = sortObject(obj[key]);
		}
		return result;
	};
	
	const sortedData = sortObject(data);
	return `${url}-${method}-${JSON.stringify(sortedData)}`;
};

export const myRequest = (options) => {
	if (options && options.withToken) {
		options.data = {
			...options.data
		};
	}
	if(options.withLoading){
		uni.showLoading({
			title: '加载中'
		});
	}
	
	const requestKey = generateRequestKey(options);
	
	return new Promise((resolve, reject) => {
		// 检查是否有相同的请求正在进行
		if (pendingRequests.has(requestKey)) {
			// 标记旧请求为取消状态
			pendingRequests.get(requestKey).isCancelled = true;
		}
		
		// 创建新的请求信息
		const requestInfo = {
			isCancelled: false,
			options
		};
		pendingRequests.set(requestKey, requestInfo);
		
		uni.request({
			url: BASE_URL + "/" + options.url,
			method: options.method || "GET",
			data: options.data || {},
			header: {
				Authorization:options.withToken? uni.getStorageSync("token"):'',
			},
			success: (res) => {
				if (res.data.code == 2) {
					uni.showToast({
						icon: 'none',
						title: '登录失效，请重新登录',
						duration: 2000
					})
					setTimeout(() => {
						uni.reLaunch({
							url:"/pages/tab/index"
						})
					}, 200);
				}
				
				// 检查请求是否被取消
				const currentRequest = pendingRequests.get(requestKey);
				if (currentRequest && currentRequest.isCancelled) {
					// 被取消的请求仍然resolve，但添加取消标记
					pendingRequests.delete(requestKey);
					resolve({
						...res,
						__isCancelled: true
					});
				} else {
					pendingRequests.delete(requestKey);
					resolve(res);
				}
			},
			fail: (err) => {
				// 检查请求是否被取消
				const currentRequest = pendingRequests.get(requestKey);
				if (currentRequest && currentRequest.isCancelled) {
					// 被取消的请求仍然resolve（不进入reject）
					pendingRequests.delete(requestKey);
					resolve({
						__isCancelled: true,
						data: {}
					});
				} else {
					uni.showToast({
						title: "请求接口失败",
					});
					pendingRequests.delete(requestKey);
					reject(err);
				}
			},
			complete() {
				uni.hideLoading();
			}
		});
	});
};
//登录判断
export const getId = () => {
	return new Promise((resolve, reject) => {
		uni.getStorage({
			key: 'info',
			success: (res) => {
				console.log(res, "登录成功");
				resolve(200);
			},
			fail: (err) => {
				console.log(err, '登录失败');
				resolve(11003);
			}
		});
	})
}