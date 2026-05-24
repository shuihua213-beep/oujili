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

const pendingRequestMap = new Map();

const getRequestKey = (options = {}) => {
	const method = (options.method || 'GET').toUpperCase();
	const url = options.url || '';
	const data = options.data || {};
	return `${method}::${url}::${JSON.stringify(data)}`;
};

const isAbortError = (error = {}) => {
	const errMsg = typeof error.errMsg === 'string' ? error.errMsg.toLowerCase() : '';
	return errMsg.includes('abort');
};

const createCanceledResponse = (options = {}, error = {}) => ({
	data: {
		code: 0,
		data: {},
		msg: 'request:cancelled'
	},
	statusCode: 0,
	header: {},
	cookies: [],
	errMsg: error.errMsg || 'request:fail abort',
	config: {
		url: options.url || '',
		method: options.method || 'GET',
		data: options.data || {}
	}
});

export const myRequest = (options = {}) => {
	const requestOptions = {
		...options,
		data: options.data ? {
			...options.data
		} : {}
	};
	if (requestOptions.withToken) {
		requestOptions.data = {
			...requestOptions.data
		};
	}
	if (requestOptions.withLoading) {
		uni.showLoading({
			title: '加载中'
		});
	}

	const requestKey = getRequestKey(requestOptions);
	const previousEntry = pendingRequestMap.get(requestKey);
	const delegatedResolves = previousEntry ? [...previousEntry.delegatedResolves, previousEntry.resolve] : [];
	if (previousEntry) {
		previousEntry.isReplaced = true;
		previousEntry.requestTask && previousEntry.requestTask.abort();
	}

	return new Promise((resolve, reject) => {
		const entry = {
			requestTask: null,
			resolve,
			reject,
			isReplaced: false,
			delegatedResolves,
			options: requestOptions
		};
		pendingRequestMap.set(requestKey, entry);

		const clearPending = () => {
			if (pendingRequestMap.get(requestKey) === entry) {
				pendingRequestMap.delete(requestKey);
			}
		};
		const resolveDelegated = (payload) => {
			entry.delegatedResolves.forEach(item => item(payload));
		};

		entry.requestTask = uni.request({
			url: BASE_URL + "/" + requestOptions.url,
			method: requestOptions.method || "GET",
			data: requestOptions.data || {},
			header: {
				Authorization: requestOptions.withToken ? uni.getStorageSync("token") : '',
			},
			success: (res) => {
				if (entry.isReplaced) {
					return;
				}
				clearPending();
				if (res.data.code == 2) {
					uni.showToast({
						icon: 'none',
						title: '登录失效，请重新登录',
						duration: 2000
					})
					setTimeout(() => {
						uni.reLaunch({
							url: "/pages/tab/index"
						})
					}, 200);
				}
				resolveDelegated(res);
				resolve(res);
			},
			fail: (err) => {
				if (entry.isReplaced && isAbortError(err)) {
					return;
				}
				clearPending();
				if (isAbortError(err)) {
					resolve(createCanceledResponse(requestOptions, err));
					return;
				}
				resolveDelegated(createCanceledResponse(requestOptions, err));
				uni.showToast({
					title: "请求接口失败",
				});
				reject(err);
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